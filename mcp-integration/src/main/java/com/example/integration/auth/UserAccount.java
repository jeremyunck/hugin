package com.example.integration.auth;

import java.util.List;

public record UserAccount(
        String username,
        String passwordHash,
        boolean enabled,
        List<String> roles) {
}
