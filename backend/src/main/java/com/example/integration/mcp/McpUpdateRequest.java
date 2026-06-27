package com.example.integration.mcp;

/**
 * Request body for updating an MCP server. All fields are optional — only non-null fields are applied.
 *
 * <p>Token handling: a non-blank {@code bearerToken} replaces the stored token; {@code clearToken=true}
 * removes it. When both are absent the existing token is left untouched. The owner is never accepted
 * here — it always comes from the authenticated session.
 */
public record McpUpdateRequest(
        String displayName,
        String endpointUrl,
        Boolean enabled,
        String authType,
        String bearerToken,
        Boolean clearToken) {
}
