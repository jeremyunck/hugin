package com.example.integration.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.jwt")
public record AuthJwtProperties(String secretBase64, String issuer, Duration tokenTtl) {

    public String issuer() {
        return issuer == null || issuer.isBlank() ? "hugin" : issuer;
    }

    public Duration tokenTtl() {
        return tokenTtl == null ? Duration.ofHours(12) : tokenTtl;
    }
}
