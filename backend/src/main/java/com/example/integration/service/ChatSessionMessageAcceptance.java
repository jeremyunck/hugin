package com.example.integration.service;

public record ChatSessionMessageAcceptance(
        String sessionId,
        String messageId,
        String runId,
        long lastSeq
) {}
