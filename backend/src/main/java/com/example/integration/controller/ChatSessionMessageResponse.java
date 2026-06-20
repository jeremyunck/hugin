package com.example.integration.controller;

public record ChatSessionMessageResponse(
        String sessionId,
        String messageId,
        String runId,
        long lastSeq
) {}
