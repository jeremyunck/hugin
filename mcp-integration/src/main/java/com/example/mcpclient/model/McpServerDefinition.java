package com.example.mcpclient.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Mirrors the standard MCP server definition format used by Claude Desktop and
 * other MCP clients (https://modelcontextprotocol.io/quickstart/user).
 *
 * <p>Stdio servers use {@code command} + {@code args} + optional {@code env}.
 * SSE/HTTP servers use {@code url} + optional {@code headers}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpServerDefinition(
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers
) {
    public ServerType resolvedType() {
        return (url != null && !url.isBlank()) ? ServerType.SSE : ServerType.STDIO;
    }

    public enum ServerType { STDIO, SSE }
}
