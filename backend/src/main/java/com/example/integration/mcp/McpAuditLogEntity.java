package com.example.integration.mcp;

import java.time.Instant;

/**
 * One append-only audit record for an MCP tool invocation. Captures who ran what, against which
 * server, with a bounded preview of the arguments and result (never full secrets), and the outcome.
 */
public record McpAuditLogEntity(
        String id,
        String ownerUsername,
        String agentId,
        String sessionId,
        String serverId,
        String toolName,
        String argumentsJson,
        String resultPreview,
        String status,
        Instant createdAt) {

    /** Invocation outcome recorded in the {@code status} column. */
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";
}
