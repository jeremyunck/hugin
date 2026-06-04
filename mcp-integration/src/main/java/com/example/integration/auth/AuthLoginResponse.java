package com.example.integration.auth;

import java.time.Instant;
import java.util.List;

public record AuthLoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        String username,
        List<String> roles) {
}
