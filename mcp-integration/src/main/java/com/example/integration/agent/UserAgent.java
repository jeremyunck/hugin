package com.example.integration.agent;

import java.time.Instant;

/** Persisted user-owned agent profile and its default system prompt. */
public record UserAgent(
        String id,
        String ownerUsername,
        String name,
        String purpose,
        String systemPrompt,
        String model,
        Instant createdAt,
        Instant updatedAt) {
}
