package com.example.integration.mcp;

import java.time.Instant;
import java.util.List;

/**
 * API view of an MCP server. Crucially this NEVER includes the bearer token — only {@code hasToken}
 * reports whether one is stored, so the secret can never be read back by any client.
 */
public record McpServerDto(
        String id,
        String name,
        String displayName,
        String transport,
        String endpointUrl,
        String authType,
        boolean enabled,
        boolean hasToken,
        int toolCount,
        int enabledToolCount,
        Instant createdAt,
        Instant updatedAt,
        List<McpToolDto> tools) {

    public static McpServerDto from(McpServerEntity server, List<McpServerToolEntity> tools) {
        List<McpToolDto> toolDtos = tools.stream().map(McpToolDto::from).toList();
        int enabledCount = (int) tools.stream().filter(t -> t.enabled() && !t.stale()).count();
        return new McpServerDto(
                server.id(),
                server.name(),
                server.displayName(),
                server.transport().name(),
                server.endpointUrl(),
                server.authType().name(),
                server.enabled(),
                server.hasToken(),
                tools.size(),
                enabledCount,
                server.createdAt(),
                server.updatedAt(),
                toolDtos);
    }
}
