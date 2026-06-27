package com.example.integration.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the Docker-backed per-session sandboxes ({@code agent.sandbox.*}).
 *
 * <p>When enabled, {@code POST /api/sandboxes} starts a container whose bind-mounted working
 * directory becomes the agent's confined workspace and into which {@code run_bash} commands are
 * routed. Subsequent chat/stream requests target the sandbox via {@code AgentRequest.sandboxId}.
 */
@ConfigurationProperties("agent.sandbox")
public record SandboxProperties(
        boolean enabled,
        String image,
        String dockerBin,
        Duration execTimeout,
        Duration startTimeout,
        String network,
        String containerPrefix,
        int maxSandboxes) {

    public SandboxProperties {
        if (image == null || image.isBlank()) {
            image = "ubuntu:24.04";
        }
        if (dockerBin == null || dockerBin.isBlank()) {
            dockerBin = "docker";
        }
        if (execTimeout == null) {
            execTimeout = Duration.ofSeconds(120);
        }
        if (startTimeout == null) {
            startTimeout = Duration.ofSeconds(120);
        }
        if (network == null) {
            network = "";
        }
        if (containerPrefix == null || containerPrefix.isBlank()) {
            containerPrefix = "bouw-sbx-";
        }
        if (maxSandboxes <= 0) {
            maxSandboxes = 25;
        }
    }
}
