package com.example.integration.mcp;

/**
 * Transport used to reach an MCP server.
 *
 * <p>Phase 1 implements only {@link #STREAMABLE_HTTP}. The enum is the extension point for future
 * transports (e.g. {@code STDIO}); add the constant here and a matching {@code McpTransportClient}
 * implementation without touching the connection/discovery/invoker services.
 */
public enum McpTransport {
    STREAMABLE_HTTP;

    // TODO(stdio): add a STDIO constant and a process-backed transport client when local MCP servers
    // are supported. The HTTP-specific fields (endpoint_url, auth) would become optional for it.

    public static McpTransport fromString(String value) {
        if (value == null || value.isBlank()) {
            return STREAMABLE_HTTP;
        }
        try {
            return McpTransport.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported MCP transport: " + value
                    + " (only STREAMABLE_HTTP is supported)");
        }
    }
}
