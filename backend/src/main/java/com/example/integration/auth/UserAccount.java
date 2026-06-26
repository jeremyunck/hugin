package com.example.integration.auth;

import java.util.List;

public record UserAccount(
        String username,
        String passwordHash,
        boolean enabled,
        List<String> roles,
        String displayName,
        String email,
        String customInstructions) {

    /** Compact constructor used by the bootstrap path that creates users without profile fields. */
    public UserAccount(String username, String passwordHash, boolean enabled, List<String> roles) {
        this(username, passwordHash, enabled, roles, null, null, null);
    }
}
