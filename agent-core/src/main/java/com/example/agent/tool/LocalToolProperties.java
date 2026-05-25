package com.example.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the built-in local tools (file access, search, shell).
 *
 * <ul>
 *   <li>{@code enabled} — master switch; when false no local tools are advertised.</li>
 *   <li>{@code workspaceRoot} — directory all file operations are confined to and the
 *       working directory for shell commands. Defaults to the process working directory.</li>
 *   <li>{@code bashTimeout} — wall-clock limit for a single shell command.</li>
 *   <li>{@code maxOutputChars} — cap on the size of any single tool result.</li>
 * </ul>
 */
@ConfigurationProperties("agent.tools")
public record LocalToolProperties(
        Boolean enabled,
        String workspaceRoot,
        Duration bashTimeout,
        Integer maxOutputChars) {

    public LocalToolProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            workspaceRoot = ".";
        }
        if (bashTimeout == null) {
            bashTimeout = Duration.ofSeconds(30);
        }
        if (maxOutputChars == null || maxOutputChars <= 0) {
            maxOutputChars = 30_000;
        }
    }
}
