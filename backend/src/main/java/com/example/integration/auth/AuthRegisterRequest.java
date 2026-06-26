package com.example.integration.auth;

public record AuthRegisterRequest(String email, String password, String confirmPassword) {
}
