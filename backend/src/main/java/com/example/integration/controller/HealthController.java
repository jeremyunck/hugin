package com.example.integration.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simple liveness probe at {@code /health}.
 *
 * Agents and scripts frequently check {@code /health} before the Spring Boot actuator path
 * ({@code /actuator/health}) is known. This endpoint returns enough information for a quick
 * sanity-check and mirrors the response shape that callers expect.
 */
@RestController
public class HealthController {

    private final String appName;

    public HealthController(@Value("${spring.application.name:bouw}") String appName) {
        this.appName = appName;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("name", appName, "status", "UP");
    }
}
