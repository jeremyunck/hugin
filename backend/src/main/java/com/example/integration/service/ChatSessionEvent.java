package com.example.integration.service;

import java.time.Instant;
import java.util.Map;

public record ChatSessionEvent(
        String id,
        String sessionId,
        String runId,
        String messageId,
        long seq,
        String type,
        String role,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {}
