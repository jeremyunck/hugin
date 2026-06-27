package com.example.agent.sandbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a project chat's isolated execution environment.
 *
 * <p>Each project chat (GitHub repository mode) owns exactly one {@code SandboxSession}: a Docker
 * container plus a named volume that holds the repository checkout. The Spring Boot backend keeps
 * the agent loop, LLM calls, and streaming; the sandbox owns the filesystem, repository checkout,
 * and all command/build/test execution. This record is the durable handle the backend uses to
 * reconnect to (or clean up) that environment across restarts.
 *
 * <p>The project uses Spring JDBC rather than JPA, so this is a plain immutable record persisted by
 * {@code SandboxSessionRepository} over the {@code sandbox_sessions} table rather than a
 * {@code @Entity}; the field shape mirrors the original design.
 *
 * @param id              primary key
 * @param chatSessionId   the chat session this sandbox belongs to
 * @param containerId     the Docker container id (assigned once the container is started)
 * @param containerName   the Docker container name ({@code bouw-agent-<id>})
 * @param dockerVolumeName the Docker volume name ({@code bouw-agent-<id>-workspace})
 * @param repositoryUrl   the clone URL of the repository checked out inside the container
 * @param repositoryBranch the branch checked out inside the container
 * @param repositoryPath  the absolute path of the checkout inside the container ({@code /workspace/repo})
 * @param status          the current lifecycle status
 * @param createdAt       when the sandbox was first created
 * @param lastUsedAt      when the sandbox was last touched by a request (drives idle expiry)
 * @param expiresAt       when the sandbox becomes eligible for idle cleanup
 */
public record SandboxSession(
        UUID id,
        UUID chatSessionId,
        String containerId,
        String containerName,
        String dockerVolumeName,
        String repositoryUrl,
        String repositoryBranch,
        String repositoryPath,
        SandboxStatus status,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt) {

    /** The sandbox id as the {@code String} the rest of the agent threads through as {@code sandboxId}. */
    public String sandboxId() {
        return id.toString();
    }

    /** Returns a copy with a new status (leaving every other field unchanged). */
    public SandboxSession withStatus(SandboxStatus newStatus) {
        return new SandboxSession(id, chatSessionId, containerId, containerName, dockerVolumeName,
                repositoryUrl, repositoryBranch, repositoryPath, newStatus, createdAt, lastUsedAt, expiresAt);
    }

    /** Returns a copy with the container id/name populated (set once the container has started). */
    public SandboxSession withContainer(String newContainerId, String newContainerName) {
        return new SandboxSession(id, chatSessionId, newContainerId, newContainerName, dockerVolumeName,
                repositoryUrl, repositoryBranch, repositoryPath, status, createdAt, lastUsedAt, expiresAt);
    }

    /** Returns a copy with refreshed last-used / expiry timestamps. */
    public SandboxSession touched(Instant now, Instant newExpiresAt) {
        return new SandboxSession(id, chatSessionId, containerId, containerName, dockerVolumeName,
                repositoryUrl, repositoryBranch, repositoryPath, status, createdAt, now, newExpiresAt);
    }
}
