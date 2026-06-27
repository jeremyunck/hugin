package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the lifecycle of a user's MCP servers: create, read, update, delete, and connectivity test.
 *
 * <p>Every operation is scoped by {@code ownerUsername}, which always comes from the authenticated
 * session — never from a request body — so users are fully isolated from each other. Bearer tokens are
 * encrypted via {@link McpSecretEncryptionService} before storage and are never returned to callers.
 */
@Service
public class McpConnectionService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionService.class);

    private final McpServerRepository serverRepository;
    private final McpServerToolRepository toolRepository;
    private final McpSecretEncryptionService encryption;
    private final McpHttpClient httpClient;
    private final Clock clock;

    public McpConnectionService(McpServerRepository serverRepository,
                                McpServerToolRepository toolRepository,
                                McpSecretEncryptionService encryption,
                                McpHttpClient httpClient,
                                Clock clock) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.encryption = encryption;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public List<McpServerEntity> list(String owner) {
        return serverRepository.findByOwner(owner);
    }

    /** Server view including its discovered tools — the shape returned by the REST API. */
    public McpServerDto toDto(McpServerEntity server) {
        return McpServerDto.from(server, toolRepository.findByServer(server.id()));
    }

    public List<McpServerDto> listDtos(String owner) {
        return list(owner).stream().map(this::toDto).toList();
    }

    /**
     * Enables or disables a single discovered tool, after verifying the tool belongs to a server owned
     * by {@code owner}. Returns the updated tool view. A disabled tool is never advertised or invoked.
     */
    public McpToolDto setToolEnabled(String owner, String serverId, String toolId, boolean enabled) {
        // require() enforces that the server (and therefore its tools) belongs to this owner.
        McpServerEntity server = require(owner, serverId);
        McpServerToolEntity tool = toolRepository.findByIdAndServer(toolId, server.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP tool not found"));
        toolRepository.setEnabled(tool.id(), enabled);
        return toolRepository.findByIdAndServer(toolId, server.id())
                .map(McpToolDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP tool not found"));
    }

    public McpServerEntity require(String owner, String id) {
        return serverRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found"));
    }

    public McpServerEntity create(String owner, McpCreateRequest request) {
        String name = sanitizeName(request.name());
        String displayName = cleanRequired(request.displayName(), "displayName");
        McpTransport transport = parseTransport(request.transport());
        String endpointUrl = validateEndpoint(request.endpointUrl());
        McpAuthType authType = parseAuthType(request.authType());
        String encryptedToken = resolveTokenForCreate(authType, request.bearerToken());

        Instant now = Instant.now(clock);
        McpServerEntity server = new McpServerEntity(
                UUID.randomUUID().toString(),
                owner,
                name,
                displayName,
                transport,
                endpointUrl,
                authType,
                encryptedToken,
                true,
                now,
                now);
        try {
            serverRepository.insert(server);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An MCP server named '" + name + "' already exists");
        }
        return server;
    }

    public McpServerEntity update(String owner, String id, McpUpdateRequest request) {
        McpServerEntity existing = require(owner, id);

        String displayName = request.displayName() != null
                ? cleanRequired(request.displayName(), "displayName")
                : existing.displayName();
        String endpointUrl = request.endpointUrl() != null
                ? validateEndpoint(request.endpointUrl())
                : existing.endpointUrl();
        boolean enabled = request.enabled() != null ? request.enabled() : existing.enabled();
        McpAuthType authType = request.authType() != null
                ? parseAuthType(request.authType())
                : existing.authType();

        String encryptedToken = resolveTokenForUpdate(existing, authType, request);

        McpServerEntity updated = new McpServerEntity(
                existing.id(),
                owner,
                existing.name(),
                displayName,
                existing.transport(),
                endpointUrl,
                authType,
                encryptedToken,
                enabled,
                existing.createdAt(),
                Instant.now(clock));
        int rows = serverRepository.update(updated);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found");
        }
        return updated;
    }

    public void delete(String owner, String id) {
        if (!serverRepository.delete(id, owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found");
        }
        // Discovered tools are removed by the ON DELETE CASCADE on mcp_server_tools.server_id.
    }

    /**
     * Tests connectivity by performing the {@code initialize} handshake. Never throws for an
     * unreachable/misbehaving server — the failure is returned as a readable {@link McpTestResponse}.
     */
    public McpTestResponse test(String owner, String id) {
        McpServerEntity server = require(owner, id);
        try {
            JsonNode info = httpClient.initialize(connectionFor(server));
            JsonNode serverInfo = info.path("serverInfo");
            return new McpTestResponse(
                    true,
                    "Connected successfully.",
                    serverInfo.path("name").asText(server.displayName()),
                    serverInfo.path("version").asText(""),
                    info.path("protocolVersion").asText(McpHttpClient.PROTOCOL_VERSION));
        } catch (McpHttpClient.McpClientException e) {
            log.info("MCP test for server {} failed: {}", server.id(), e.getMessage());
            return McpTestResponse.failure(e.getMessage());
        }
    }

    /**
     * Builds a transport connection for a server, decrypting its bearer token when present. Used by
     * discovery and invocation. Decrypted tokens never leave the MCP package.
     */
    public McpHttpClient.Connection connectionFor(McpServerEntity server) {
        String token = null;
        if (server.authType() == McpAuthType.BEARER_TOKEN && server.hasToken()) {
            try {
                token = encryption.decrypt(server.accessTokenEncrypted());
            } catch (RuntimeException e) {
                // Most likely the encryption secret changed. Surface a clear, non-leaking message.
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Stored credentials for this MCP server could not be decrypted. Re-enter the bearer token.");
            }
        }
        return new McpHttpClient.Connection(server.endpointUrl(), token);
    }

    /* ----------------------------- validation / parsing helpers ----------------------------- */

    private String resolveTokenForCreate(McpAuthType authType, String bearerToken) {
        if (authType != McpAuthType.BEARER_TOKEN) {
            return null;
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A bearer token is required when authType is BEARER_TOKEN");
        }
        return encryption.encrypt(bearerToken);
    }

    private String resolveTokenForUpdate(McpServerEntity existing, McpAuthType authType,
                                         McpUpdateRequest request) {
        if (authType != McpAuthType.BEARER_TOKEN) {
            // Switching to NONE (or staying NONE) drops any stored token.
            return null;
        }
        if (Boolean.TRUE.equals(request.clearToken())) {
            return null;
        }
        if (request.bearerToken() != null && !request.bearerToken().isBlank()) {
            return encryption.encrypt(request.bearerToken());
        }
        // No token change requested — keep the existing one (which may be null if not yet set).
        return existing.authType() == McpAuthType.BEARER_TOKEN ? existing.accessTokenEncrypted() : null;
    }

    private static McpTransport parseTransport(String value) {
        try {
            return McpTransport.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static McpAuthType parseAuthType(String value) {
        try {
            return McpAuthType.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Normalizes a server name into a short, slug-like identifier (lowercase, {@code [a-z0-9_-]}). This
     * is the basis of the {@code mcp_<server>_<tool>} advertised names, so it must be model-safe.
     */
    static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String slug = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        slug = slug.replaceAll("(^-+)|(-+$)", "");
        if (slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name must contain at least one letter or digit");
        }
        if (slug.length() > 64) {
            slug = slug.substring(0, 64);
        }
        return slug;
    }

    private static String validateEndpoint(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpointUrl is required");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endpointUrl must be an http(s) URL");
        }
        return trimmed;
    }

    private static String cleanRequired(String value, String field) {
        if (value == null || value.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }
}
