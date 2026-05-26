package com.example.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the cloud-agent feature ({@code agent.cloud.*}).
 *
 * <p>Cloud agents are disabled by default ({@code agent.cloud.enabled=false}). When enabled,
 * each {@code POST /api/agents} call clones a repository into
 * {@code $AGENT_HOME/agents/<id>/} and runs the agent loop against it.
 */
@ConfigurationProperties("agent.cloud")
public record CloudAgentProperties(
        boolean enabled,
        int maxConcurrent,
        String githubToken,
        String branchPrefix,
        boolean cleanupOnComplete) {

    public CloudAgentProperties {
        if (maxConcurrent <= 0) {
            maxConcurrent = 3;
        }
        if (branchPrefix == null || branchPrefix.isBlank()) {
            branchPrefix = "agent";
        }
        if (githubToken == null) {
            githubToken = "";
        }
    }
}
