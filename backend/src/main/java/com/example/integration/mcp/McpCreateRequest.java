package com.example.integration.mcp;

/**
 * Request body for creating an MCP server. The owner is ALWAYS taken from the authenticated session,
 * never from this body.
 */
public record McpCreateRequest(
        String name,
        String displayName,
        String transport,
        String endpointUrl,
        String authType,
        String bearerToken) {
}
