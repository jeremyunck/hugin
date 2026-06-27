package com.example.integration.mcp;

import java.time.Instant;

/**
 * Persisted MCP server connection owned by a single user.
 *
 * <p>{@code accessTokenEncrypted} holds the bearer token encrypted at rest (or {@code null} when the
 * server uses {@link McpAuthType#NONE}); it is never exposed outside the MCP package. All lookups are
 * scoped by {@code ownerUsername} so connections are strictly isolated per user.
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
        Instant updatedAt) {

    /** Whether this server stores an (encrypted) bearer token. */
    public boolean hasToken() {
        return accessTokenEncrypted != null && !accessTokenEncrypted.isBlank();
    }
}
