package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.integration.mcp.McpHttpClient.McpClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the lifecycle of a user's MCP servers: create, read, update, delete, and connectivity test.
 *
 * <p>Every operation is scoped by {@code ownerUsername}, which always comes from the authenticated
 * session — never from a request body — so users are fully isolated. Static bearer tokens, OAuth
 * tokens, and client secrets are encrypted via {@link McpSecretEncryptionService} and never returned.
 * Transport- and auth-specific extras (stdio command/args/env; OAuth endpoints/client/tokens) live in
 * {@link McpServerConfig}, serialized into the {@code config_json} column.
 */
@Service
public class McpConnectionService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionService.class);

    private final McpServerRepository serverRepository;
    private final McpServerToolRepository toolRepository;
    private final McpSecretEncryptionService encryption;
    private final McpTransports transports;
    private final McpCredentialResolver credentialResolver;
    private final McpSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public McpConnectionService(McpServerRepository serverRepository,
                                McpServerToolRepository toolRepository,
                                McpSecretEncryptionService encryption,
                                McpTransports transports,
                                McpCredentialResolver credentialResolver,
                                McpSessionManager sessionManager,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.encryption = encryption;
        this.transports = transports;
        this.credentialResolver = credentialResolver;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<McpServerEntity> list(String owner) {
        return serverRepository.findByOwner(owner);
    }

    /** Server view including its discovered tools — the shape returned by the REST API. */
    public McpServerDto toDto(McpServerEntity server) {
        return McpServerDto.from(server, toolRepository.findByServer(server.id()),
                McpServerConfig.parse(objectMapper, server.configJson()));
    }

    public List<McpServerDto> listDtos(String owner) {
        return list(owner).stream().map(this::toDto).toList();
    }

    public McpToolDto setToolEnabled(String owner, String serverId, String toolId, boolean enabled) {
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
        McpAuthType authType = parseAuthType(request.authType());

        String endpointUrl = null;
        String encryptedToken = null;
        McpServerConfig config = McpServerConfig.empty();

        if (transport == McpTransport.STDIO) {
            requireStdioEnabled();
            if (authType != McpAuthType.NONE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "stdio servers do not support HTTP authentication (use authType NONE)");
            }
            config = new McpServerConfig(buildStdio(request.command(), request.args(), request.env(), true), null);
        } else {
            endpointUrl = validateEndpoint(request.endpointUrl());
            if (authType == McpAuthType.BEARER_TOKEN) {
                encryptedToken = encryptRequiredToken(request.bearerToken());
            } else if (authType == McpAuthType.OAUTH) {
                config = new McpServerConfig(null, buildInitialOAuth(request, endpointUrl));
            }
        }

        Instant now = Instant.now(clock);
        McpServerEntity server = new McpServerEntity(
                UUID.randomUUID().toString(), owner, name, displayName, transport, endpointUrl,
                authType, encryptedToken, true, now, now, config.toJson(objectMapper));
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
        McpServerConfig config = McpServerConfig.parse(objectMapper, existing.configJson());

        String displayName = request.displayName() != null
                ? cleanRequired(request.displayName(), "displayName") : existing.displayName();
        boolean enabled = request.enabled() != null ? request.enabled() : existing.enabled();
        McpAuthType authType = request.authType() != null
                ? parseAuthType(request.authType()) : existing.authType();

        String endpointUrl = existing.endpointUrl();
        String encryptedToken = existing.accessTokenEncrypted();

        if (existing.transport() == McpTransport.STDIO) {
            if (request.command() != null || request.args() != null || request.env() != null) {
                McpServerConfig.Stdio current = config.stdio();
                String command = request.command() != null ? request.command()
                        : (current == null ? null : current.command());
                config = new McpServerConfig(
                        buildStdio(command,
                                request.args() != null ? request.args() : (current == null ? null : current.args()),
                                request.env() != null ? request.env() : (current == null ? null : current.env()),
                                true),
                        config.oauth());
            }
            authType = McpAuthType.NONE;
        } else {
            if (request.endpointUrl() != null) {
                endpointUrl = validateEndpoint(request.endpointUrl());
            }
            encryptedToken = resolveTokenForUpdate(existing, authType, request);
            config = applyOAuthUpdate(config, authType, endpointUrl, request);
        }

        McpServerEntity updated = new McpServerEntity(
                existing.id(), owner, existing.name(), displayName, existing.transport(), endpointUrl,
                authType, encryptedToken, enabled, existing.createdAt(), Instant.now(clock),
                config.toJson(objectMapper));
        int rows = serverRepository.update(updated);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found");
        }
        // Drop any cached live session so the next call reflects the new endpoint/credential/config.
        sessionManager.invalidate(id);
        return updated;
    }

    public void delete(String owner, String id) {
        if (!serverRepository.delete(id, owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found");
        }
        sessionManager.invalidate(id);
        // Discovered tools and OAuth state rows are removed by ON DELETE CASCADE.
    }

    /**
     * Tests connectivity by opening a session (which performs {@code initialize}). Never throws for an
     * unreachable/misbehaving server — the failure is returned as a readable {@link McpTestResponse}.
     */
    public McpTestResponse test(String owner, String id) {
        McpServerEntity server = require(owner, id);
        if (server.authType() == McpAuthType.OAUTH && credentialResolver.resolveBearer(server) == null) {
            return McpTestResponse.failure("This server requires OAuth authorization. Click “Connect” first.");
        }
        try (McpSession session = transports.openSession(server, credentialResolver.resolveBearer(server))) {
            JsonNode info = session.serverInfo();
            JsonNode serverInfo = info == null ? null : info.path("serverInfo");
            return new McpTestResponse(
                    true,
                    "Connected successfully.",
                    serverInfo == null ? server.displayName() : serverInfo.path("name").asText(server.displayName()),
                    serverInfo == null ? "" : serverInfo.path("version").asText(""),
                    info == null ? McpHttpClient.PROTOCOL_VERSION
                            : info.path("protocolVersion").asText(McpHttpClient.PROTOCOL_VERSION));
        } catch (McpClientException e) {
            log.info("MCP test for server {} failed: {}", server.id(), e.getMessage());
            return McpTestResponse.failure(e.getMessage());
        }
    }

    /* ----------------------------- validation / parsing helpers ----------------------------- */

    private void requireStdioEnabled() {
        if (!transports.stdioEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The stdio transport is disabled on this server (set mcp.stdio.enabled=true to allow it).");
        }
    }

    private McpServerConfig.Stdio buildStdio(String command, List<String> args,
                                             java.util.Map<String, String> env, boolean requireCommand) {
        if (requireCommand && (command == null || command.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command is required for stdio servers");
        }
        return new McpServerConfig.Stdio(command == null ? null : command.trim(), args, env);
    }

    private McpServerConfig.OAuth buildInitialOAuth(McpCreateRequest request, String endpointUrl) {
        String clientSecretEnc = (request.oauthClientSecret() == null || request.oauthClientSecret().isBlank())
                ? null : encryption.encrypt(request.oauthClientSecret());
        return new McpServerConfig.OAuth(
                blankToNull(request.oauthClientId()),
                clientSecretEnc,
                blankToNull(request.oauthAuthorizationEndpoint()),
                blankToNull(request.oauthTokenEndpoint()),
                blankToNull(request.oauthRegistrationEndpoint()),
                blankToNull(request.oauthScope()),
                endpointUrl,
                null, null, null);
    }

    private McpServerConfig applyOAuthUpdate(McpServerConfig config, McpAuthType authType,
                                             String endpointUrl, McpUpdateRequest request) {
        if (authType != McpAuthType.OAUTH) {
            // Leaving OAuth (or never using it) clears any stored OAuth config/tokens.
            return new McpServerConfig(config.stdio(), null);
        }
        McpServerConfig.OAuth oauth = config.oauth();
        if (oauth == null) {
            oauth = new McpServerConfig.OAuth(null, null, null, null, null,
                    blankToNull(request.oauthScope()), endpointUrl, null, null, null);
        } else if (request.oauthScope() != null) {
            oauth = new McpServerConfig.OAuth(oauth.clientId(), oauth.clientSecretEncrypted(),
                    oauth.authorizationEndpoint(), oauth.tokenEndpoint(), oauth.registrationEndpoint(),
                    blankToNull(request.oauthScope()), endpointUrl, oauth.accessTokenEncrypted(),
                    oauth.refreshTokenEncrypted(), oauth.accessTokenExpiresAtEpochMs());
        }
        return new McpServerConfig(config.stdio(), oauth);
    }

    private String encryptRequiredToken(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A bearer token is required when authType is BEARER_TOKEN");
        }
        return encryption.encrypt(bearerToken);
    }

    private String resolveTokenForUpdate(McpServerEntity existing, McpAuthType authType,
                                         McpUpdateRequest request) {
        if (authType != McpAuthType.BEARER_TOKEN) {
            return null;
        }
        if (Boolean.TRUE.equals(request.clearToken())) {
            return null;
        }
        if (request.bearerToken() != null && !request.bearerToken().isBlank()) {
            return encryption.encrypt(request.bearerToken());
        }
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpointUrl must be an http(s) URL");
        }
        return trimmed;
    }

    private static String cleanRequired(String value, String field) {
        if (value == null || value.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
