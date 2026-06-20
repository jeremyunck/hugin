package com.example.integration.controller;

import java.time.Instant;
import java.util.Map;

public record ChatSessionEventResponse(
        String id,
        long seq,
        String type,
        String messageId,
        String runId,
        String role,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {}
