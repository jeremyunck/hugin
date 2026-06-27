package com.example.integration.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.integration.mcp.McpHttpClient.McpClientException;
import com.example.integration.mcp.McpHttpClient.McpSessionExpiredException;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches live {@link McpSession}s per server so repeated tool calls reuse one connection instead of
 * re-handshaking every time. This is the "persistent sessions" path: an HTTP session keeps its
 * negotiated {@code Mcp-Session-Id}; a stdio session keeps its child process alive.
 *
 * <p>A cached session is keyed by server id and fingerprinted by transport + endpoint + credential, so
 * a rotated token or changed endpoint transparently re-opens. If the server has expired the session
 * (HTTP 404 → {@link McpSessionExpiredException}), the manager re-opens once and retries. Sessions idle
 * past {@code mcp.session.idle-timeout-seconds} are closed and evicted lazily on the next access.
 *
 * <p>Calls on a single cached session are serialized (per-server lock) since one MCP connection is not
 * safe for concurrent in-flight requests.
 */
@Component
public class McpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(McpSessionManager.class);

    private final McpTransports transports;
    private final Duration idleTimeout;
    private final Map<String, CachedSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public McpSessionManager(McpTransports transports,
                             @Value("${mcp.session.idle-timeout-seconds:300}") long idleTimeoutSeconds) {
        this.transports = transports;
        this.idleTimeout = Duration.ofSeconds(idleTimeoutSeconds);
    }

    private static final class CachedSession {
        private final McpSession session;
        private final String fingerprint;
        private volatile Instant lastUsed;

        private CachedSession(McpSession session, String fingerprint) {
            this.session = session;
            this.fingerprint = fingerprint;
            this.lastUsed = Instant.now();
        }
    }

    /** Invokes a tool on a reused (or freshly opened) session, re-opening once if the session expired. */
    public String callTool(McpServerEntity server, String bearerToken, String toolName,
                           Map<String, Object> arguments) throws McpClientException {
        evictIdle();
        String fingerprint = fingerprint(server, bearerToken);
        synchronized (lockFor(server.id())) {
            CachedSession cached = acquire(server, bearerToken, fingerprint);
            try {
                cached.lastUsed = Instant.now();
                return cached.session.callTool(toolName, arguments);
            } catch (McpSessionExpiredException expired) {
                log.debug("MCP session for {} expired; re-opening.", server.id());
                invalidate(server.id());
                CachedSession reopened = acquire(server, bearerToken, fingerprint);
                reopened.lastUsed = Instant.now();
                return reopened.session.callTool(toolName, arguments);
            }
        }
    }

    private CachedSession acquire(McpServerEntity server, String bearerToken, String fingerprint)
            throws McpClientException {
        CachedSession existing = sessions.get(server.id());
        if (existing != null && existing.fingerprint.equals(fingerprint)) {
            return existing;
        }
        if (existing != null) {
            closeQuietly(existing.session);
            sessions.remove(server.id());
        }
        McpSession session = transports.openSession(server, bearerToken);
        CachedSession fresh = new CachedSession(session, fingerprint);
        sessions.put(server.id(), fresh);
        return fresh;
    }

    /** Drops and closes any cached session for a server (called on update/delete/expiry). */
    public void invalidate(String serverId) {
        CachedSession removed = sessions.remove(serverId);
        if (removed != null) {
            closeQuietly(removed.session);
        }
    }

    private void evictIdle() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (Iterator<Map.Entry<String, CachedSession>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, CachedSession> entry = it.next();
            if (entry.getValue().lastUsed.isBefore(cutoff)) {
                closeQuietly(entry.getValue().session);
                it.remove();
            }
        }
    }

    private Object lockFor(String serverId) {
        return locks.computeIfAbsent(serverId, k -> new Object());
    }

    private static void closeQuietly(McpSession session) {
        try {
            session.close();
        } catch (RuntimeException e) {
            log.debug("Error closing MCP session (ignored): {}", e.getMessage());
        }
    }

    private static String fingerprint(McpServerEntity server, String bearerToken) {
        String raw = server.transport().name() + "\0" + (server.endpointUrl() == null ? "" : server.endpointUrl())
                + "\0" + (server.configJson() == null ? "" : server.configJson())
                + "\0" + (bearerToken == null ? "" : bearerToken);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
