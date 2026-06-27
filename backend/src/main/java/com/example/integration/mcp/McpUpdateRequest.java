package com.example.integration.mcp;

import java.util.List;
import java.util.Map;

/**
 * Request body for updating an MCP server. All fields are optional — only non-null fields are applied.
 *
 * <p>Token handling: a non-blank {@code bearerToken} replaces the stored token; {@code clearToken=true}
 * removes it. When both are absent the existing token is left untouched. For stdio servers,
 * {@code command}/{@code args}/{@code env} update the launch spec. The owner and transport are never
 * changed here; the owner always comes from the authenticated session.
 */
public record McpUpdateRequest(
        String displayName,
        String endpointUrl,
        Boolean enabled,
        String authType,
        String bearerToken,
        Boolean clearToken,
        String command,
        List<String> args,
        Map<String, String> env,
        String oauthScope) {

    /** Convenience constructor for the common case (no stdio/OAuth-scope changes). */
    public McpUpdateRequest(String displayName, String endpointUrl, Boolean enabled, String authType,
                            String bearerToken, Boolean clearToken) {
        this(displayName, endpointUrl, enabled, authType, bearerToken, clearToken, null, null, null, null);
    }
}
