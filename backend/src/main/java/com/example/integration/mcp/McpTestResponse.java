package com.example.integration.mcp;

/** Result of testing connectivity to an MCP server ({@code POST /servers/{id}/test}). */
public record McpTestResponse(
        boolean success,
        String message,
        String serverName,
        String serverVersion,
        String protocolVersion) {

    public static McpTestResponse failure(String message) {
        return new McpTestResponse(false, message, null, null, null);
    }
}
