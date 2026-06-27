package com.example.integration.mcp;

import java.time.Instant;

/**
 * A tool discovered from an MCP server.
 *
 * <p>{@code toolName} is the upstream server's own name (used for {@code tools/call});
 * {@code huginToolName} is the sanitized, collision-free name advertised to the model
 * ({@code mcp_<server>_<tool>}). {@code stale} marks a tool that disappeared from a later discovery —
 * it is kept (not deleted) so the user's {@code enabled} choice survives if the tool reappears, but a
 * stale tool is never advertised or invokable.
 */
public record McpServerToolEntity(
        String id,
        String serverId,
        String toolName,
        String huginToolName,
        String description,
        String inputSchemaJson,
        boolean enabled,
        boolean stale,
        Instant lastSeenAt) {
}
