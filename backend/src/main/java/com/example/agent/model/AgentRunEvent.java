package com.example.agent.model;

import java.time.Instant;

/** A persisted event from a regular /api/agent/stream run. */
public record AgentRunEvent(
        String runId,
        String owner,
        String sessionId,
        long eventId,
        String type,
        Instant createdAt,
        String dataJson) {}
