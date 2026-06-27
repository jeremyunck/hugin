package com.example.integration.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the fully isolated, containerized project-chat sandboxes ({@code bouw.sandbox.*}).
 *
 * <p>Each project chat (GitHub repository mode) gets its own Docker container plus a named volume that
 * holds the repository checkout. Unlike the legacy {@code agent.sandbox.*} bind-mount sandboxes, the
 * repository here lives <em>only</em> inside the container volume — never on the host — so all file and
 * shell tools execute inside the container. These settings control the image, resource limits, and
 * idle-expiry of those containers.
 *
 * @param enabled         master switch for the isolated project-chat sandbox runtime
 * @param image           container image new sandboxes run ({@code bouw-agent-sandbox:latest})
 * @param idleTimeoutHours hours a sandbox may sit unused before the cleanup job destroys it
 * @param dockerBin       docker CLI binary
 * @param memory          per-container memory limit ({@code --memory})
 * @param cpus            per-container CPU limit ({@code --cpus})
 * @param pidsLimit       per-container process limit ({@code --pids-limit})
 * @param network         docker network for containers (blank = default bridge)
 * @param containerPrefix prefix for container/volume names ({@code bouw-agent-})
 * @param workspacePath   the volume mount point inside the container ({@code /workspace})
 * @param repoSubdir      the repository checkout directory under the workspace ({@code repo})
 * @param execTimeout     default wall-clock limit for a single in-container command
 * @param startTimeout    wall-clock limit for starting a container
 * @param cloneTimeout    wall-clock limit for the in-container repository clone
 */
@ConfigurationProperties("bouw.sandbox")
public record ProjectSandboxProperties(
        boolean enabled,
        String image,
        int idleTimeoutHours,
        String dockerBin,
        String memory,
        String cpus,
        int pidsLimit,
        String network,
        String containerPrefix,
        String workspacePath,
        String repoSubdir,
        Duration execTimeout,
        Duration startTimeout,
        Duration cloneTimeout) {

    public ProjectSandboxProperties {
        if (image == null || image.isBlank()) {
            image = "bouw-agent-sandbox:latest";
        }
        if (idleTimeoutHours <= 0) {
            idleTimeoutHours = 72;
        }
        if (dockerBin == null || dockerBin.isBlank()) {
            dockerBin = "docker";
        }
        if (memory == null || memory.isBlank()) {
            memory = "4g";
        }
        if (cpus == null || cpus.isBlank()) {
            cpus = "2";
        }
        if (pidsLimit <= 0) {
            pidsLimit = 512;
        }
        if (network == null) {
            network = "";
        }
        if (containerPrefix == null || containerPrefix.isBlank()) {
            containerPrefix = "bouw-agent-";
        }
        if (workspacePath == null || workspacePath.isBlank()) {
            workspacePath = "/workspace";
        }
        if (repoSubdir == null || repoSubdir.isBlank()) {
            repoSubdir = "repo";
        }
        if (execTimeout == null) {
            execTimeout = Duration.ofSeconds(120);
        }
        if (startTimeout == null) {
            startTimeout = Duration.ofSeconds(120);
        }
        if (cloneTimeout == null) {
            cloneTimeout = Duration.ofSeconds(300);
        }
    }

    /** The container name for a sandbox id: {@code bouw-agent-<id>}. */
    public String containerName(String sandboxId) {
        return containerPrefix + sandboxId;
    }

    /** The docker volume name for a sandbox id: {@code bouw-agent-<id>-workspace}. */
    public String volumeName(String sandboxId) {
        return containerPrefix + sandboxId + "-workspace";
    }

    /** Absolute path of the repository checkout inside the container ({@code /workspace/repo}). */
    public String repositoryPath() {
        String base = workspacePath.endsWith("/") ? workspacePath.substring(0, workspacePath.length() - 1) : workspacePath;
        return base + "/" + repoSubdir;
    }
}
