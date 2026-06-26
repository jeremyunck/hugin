package com.example.integration.auth;

import java.time.Instant;
import java.util.List;

public record AuthMeResponse(
        String username,
        List<String> roles,
        Instant issuedAt,
        Instant expiresAt,
        String displayName,
        String email,
        String customInstructions) {
}
