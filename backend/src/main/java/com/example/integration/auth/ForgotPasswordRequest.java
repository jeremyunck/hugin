package com.example.integration.auth;

/**
 * First step of the forgotten-password flow on the login screen (no session): the email of the
 * account to recover plus the new password (and its confirmation) the caller wants to set. The new
 * password is only applied after the emailed verification code is confirmed via
 * {@code /api/auth/password/forgot/verify}.
 */
public record ForgotPasswordRequest(String email, String newPassword, String confirmPassword) {
}
