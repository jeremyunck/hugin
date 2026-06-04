package com.example.agent.model;

import java.time.Instant;

/** A persisted cloud-agent SSE event. */
public record CloudAgentEvent(
        String agentId,
        String type,
        Instant createdAt,
        String dataJson) {}
