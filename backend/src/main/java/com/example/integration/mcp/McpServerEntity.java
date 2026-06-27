package com.example.integration.mcp;

import java.time.Instant;

/**
 * Persisted MCP server connection owned by a single user.
 *
 * <p>{@code accessTokenEncrypted} holds the bearer token encrypted at rest (or {@code null} for
 * {@link McpAuthType#NONE}/{@link McpAuthType#OAUTH}); it is never exposed outside the MCP package.
 * {@code configJson} carries transport- and auth-specific extra configuration (stdio command/args/env;
 * OAuth client/endpoints/scope and individually-encrypted tokens) — see {@link McpServerConfig}. All
 * lookups are scoped by {@code ownerUsername} so connections are strictly isolated per user.
 */
public record McpServerEntity(
        String id,
        String ownerUsername,
        String name,
        String displayName,
        McpTransport transport,
        String endpointUrl,
        McpAuthType authType,
        String accessTokenEncrypted,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        String configJson) {

    /**
     * Back-compatible constructor without {@code configJson} (defaults to {@code null}). Keeps existing
     * call sites and tests working unchanged.
     */
    public McpServerEntity(
            String id,
            String ownerUsername,
            String name,
            String displayName,
            McpTransport transport,
            String endpointUrl,
            McpAuthType authType,
            String accessTokenEncrypted,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        this(id, ownerUsername, name, displayName, transport, endpointUrl, authType,
                accessTokenEncrypted, enabled, createdAt, updatedAt, null);
    }

    /** Whether this server stores an (encrypted) static bearer token. */
    public boolean hasToken() {
        return accessTokenEncrypted != null && !accessTokenEncrypted.isBlank();
    }

    /** Returns a copy with the given config JSON. */
    public McpServerEntity withConfigJson(String newConfigJson) {
        return new McpServerEntity(id, ownerUsername, name, displayName, transport, endpointUrl,
                authType, accessTokenEncrypted, enabled, createdAt, updatedAt, newConfigJson);
    }
}
