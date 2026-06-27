package com.example.integration.mcp;

/**
 * Transport used to reach an MCP server.
 *
 * <p>{@link #STREAMABLE_HTTP} reaches a remote server over HTTP; {@link #STDIO} runs a local server as
 * a child process (gated by {@code mcp.stdio.enabled}; see {@link McpStdioClient}). New transports are
 * added by extending this enum and {@link McpTransports}.
 */
public enum McpTransport {
    STREAMABLE_HTTP,
    STDIO;

    /** Whether this transport reaches a server over the network (and therefore needs an endpoint URL). */
    public boolean isNetwork() {
        return this == STREAMABLE_HTTP;
    }

    public static McpTransport fromString(String value) {
        if (value == null || value.isBlank()) {
            return STREAMABLE_HTTP;
        }
        try {
            return McpTransport.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported MCP transport: " + value
                    + " (supported: STREAMABLE_HTTP, STDIO)");
        }
    }
}
