package com.example.integration.auth;

/**
 * Returned by the first step of registration and login. It tells the client that a 6-digit code was
 * emailed and that it must call {@code /api/auth/verify} with that code to obtain a session token.
 */
public record AuthChallengeResponse(String email, boolean verificationRequired, String message) {
}
