package com.example.integration.mcp;

import java.util.Locale;

/**
 * Authentication scheme used when calling an MCP server.
 *
 * <p>{@link #NONE} and {@link #BEARER_TOKEN} use a static (or absent) credential. {@link #OAUTH} runs
 * an OAuth 2.1 Authorization Code + PKCE flow (with Dynamic Client Registration when the server
 * supports it), storing access/refresh tokens encrypted at rest and refreshing them transparently; the
 * resulting access token is sent as a bearer. See {@link McpOAuthService}.
 */
public enum McpAuthType {
    NONE,
    BEARER_TOKEN,
    OAUTH;

    public static McpAuthType fromString(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return McpAuthType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported MCP auth type: " + value
                    + " (supported: NONE, BEARER_TOKEN, OAUTH)");
        }
    }
}
