package com.example.integration.controller;

import java.util.List;

public record ChatSessionEventsResponse(
        String sessionId,
        List<ChatSessionEventResponse> events
) {}
