package com.example.integration.mcp;

import java.time.Instant;

/** API view of a discovered MCP tool. Never carries secrets. */
public record McpToolDto(
        String id,
        String toolName,
        String bouwToolName,
        String description,
        boolean enabled,
        boolean stale,
        Instant lastSeenAt) {

    public static McpToolDto from(McpServerToolEntity tool) {
        return new McpToolDto(
                tool.id(),
                tool.toolName(),
                tool.bouwToolName(),
                tool.description(),
                tool.enabled(),
                tool.stale(),
                tool.lastSeenAt());
    }
}
