package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Runs MCP tool discovery and reconciles the result into the database.
 *
 * <p>Discovery performs {@code initialize} + {@code tools/list} and then upserts each tool:
 * <ul>
 *   <li>A tool seen before (same {@code server_id} + {@code tool_name}) keeps its {@code enabled}
 *       state and advertised {@code bouw_tool_name}; only its schema/description and freshness are
 *       refreshed.</li>
 *   <li>A new tool is inserted with a sanitized, collision-free {@code mcp_<server>_<tool>} name and is
 *       enabled by default.</li>
 *   <li>A tool that has disappeared is marked stale (not deleted), so the user's enable choice
 *       survives if it returns.</li>
 * </ul>
 */
@Service
public class McpDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(McpDiscoveryService.class);
    private static final int MAX_BOUW_NAME_LENGTH = 200;

    private final McpServerToolRepository toolRepository;
    private final McpTransports transports;
    private final McpCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public McpDiscoveryService(McpServerToolRepository toolRepository,
                               McpTransports transports,
                               McpCredentialResolver credentialResolver,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.toolRepository = toolRepository;
        this.transports = transports;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Discovers and reconciles tools for a server. Never throws for a transport/protocol failure — the
     * outcome is returned as a readable {@link McpDiscoveryResponse}. Opens a one-shot session (closed
     * immediately) rather than reusing the cached one, so discovery always reflects fresh state.
     */
    public McpDiscoveryResponse discover(McpServerEntity server) {
        List<McpHttpClient.DiscoveredTool> discovered;
        try (McpSession session = transports.openSession(server, credentialResolver.resolveBearer(server))) {
            discovered = session.listTools();
        } catch (McpHttpClient.McpClientException e) {
            log.info("MCP discovery for server {} failed: {}", server.id(), e.getMessage());
            return McpDiscoveryResponse.failure(e.getMessage());
        }

        Instant now = Instant.now(clock);
        Set<String> seen = new HashSet<>();
        for (McpHttpClient.DiscoveredTool tool : discovered) {
            seen.add(tool.name());
            upsertTool(server, tool, now);
        }

        // Anything previously known but not seen this run is marked stale (kept, not deleted).
        int staleCount = 0;
        for (McpServerToolEntity existing : toolRepository.findByServer(server.id())) {
            if (!seen.contains(existing.toolName()) && !existing.stale()) {
                toolRepository.setStale(existing.id(), true);
                staleCount++;
            }
        }

        List<McpServerToolEntity> current = toolRepository.findByServer(server.id());
        String message = "Discovered " + discovered.size() + " tool(s)."
                + (staleCount > 0 ? " " + staleCount + " previously known tool(s) are no longer offered." : "");
        return new McpDiscoveryResponse(true, message, discovered.size(),
                current.stream().map(McpToolDto::from).toList());
    }

    private void upsertTool(McpServerEntity server, McpHttpClient.DiscoveredTool tool, Instant now) {
        String schemaJson = serializeSchema(tool.inputSchema());
        toolRepository.findByServerAndToolName(server.id(), tool.name()).ifPresentOrElse(existing -> {
            // Schema may have changed; refresh it and clear staleness, but keep the enabled state and
            // the already-advertised bouw name so the model sees a stable tool identity.
            McpServerToolEntity refreshed = new McpServerToolEntity(
                    existing.id(),
                    existing.serverId(),
                    existing.toolName(),
                    existing.bouwToolName(),
                    tool.description(),
                    schemaJson,
                    existing.enabled(),
                    false,
                    now);
            toolRepository.updateOnRediscovery(refreshed);
        }, () -> {
            String bouwName = generateBouwToolName(server.name(), tool.name(), server.id());
            McpServerToolEntity created = new McpServerToolEntity(
                    UUID.randomUUID().toString(),
                    server.id(),
                    tool.name(),
                    bouwName,
                    tool.description(),
                    schemaJson,
                    true,
                    false,
                    now);
            toolRepository.insert(created);
        });
    }

    private String serializeSchema(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            // A permissive default object schema keeps the advertised tool valid when a server omits one.
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        return schema.toString();
    }

    /**
     * Builds the model-facing tool name {@code mcp_<serverSlug>_<toolSlug>}, sanitized to
     * {@code [a-z0-9_]} and guaranteed globally unique (the {@code bouw_tool_name} column is unique).
     * On collision with a different server's tool, a short server-id suffix disambiguates; a numeric
     * suffix is the final fallback.
     */
    String generateBouwToolName(String serverSlug, String toolName, String serverId) {
        String base = "mcp_" + slug(serverSlug) + "_" + slug(toolName);
        if (base.length() > MAX_BOUW_NAME_LENGTH) {
            base = base.substring(0, MAX_BOUW_NAME_LENGTH);
        }
        if (isAvailable(base)) {
            return base;
        }
        String shortId = serverId.replace("-", "");
        shortId = shortId.substring(0, Math.min(8, shortId.length()));
        String candidate = trimTo(base, MAX_BOUW_NAME_LENGTH - shortId.length() - 1) + "_" + shortId;
        if (isAvailable(candidate)) {
            return candidate;
        }
        for (int i = 2; i < 1000; i++) {
            String suffix = "_" + i;
            String numbered = trimTo(base, MAX_BOUW_NAME_LENGTH - suffix.length()) + suffix;
            if (isAvailable(numbered)) {
                return numbered;
            }
        }
        // Extremely unlikely; a UUID fragment guarantees termination.
        return ("mcp_" + UUID.randomUUID().toString().replace("-", "")).substring(0, MAX_BOUW_NAME_LENGTH);
    }

    private boolean isAvailable(String bouwName) {
        return toolRepository.findByBouwToolName(bouwName).isEmpty();
    }

    private static String trimTo(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(0, max));
    }

    /** Lowercases and replaces every run of non-{@code [a-z0-9]} characters with a single underscore. */
    private static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "tool";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("(^_+)|(_+$)", "");
        return s.isBlank() ? "tool" : s;
    }
}
