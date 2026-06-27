package com.example.integration.mcp;

import java.util.Locale;

/**
 * Authentication scheme used when calling an MCP server.
 *
 * <p>Phase 1 implements {@link #NONE} and {@link #BEARER_TOKEN}. The enum is the extension point for
 * future schemes (e.g. {@code OAUTH}); add the constant here and the corresponding header/credential
 * handling without touching the rest of the MCP package.
 */
public enum McpAuthType {
    NONE,
    BEARER_TOKEN;

    // TODO(oauth): add an OAUTH constant plus token-exchange / refresh handling and dynamic client
    // registration support. Stored credentials would move from a single bearer token to an OAuth
    // token set, still encrypted at rest via McpSecretEncryptionService.

    public static McpAuthType fromString(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return McpAuthType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported MCP auth type: " + value
                    + " (supported: NONE, BEARER_TOKEN)");
        }
    }
}
