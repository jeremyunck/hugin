package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps cloud-agent session IDs to their per-agent {@link Workspace}.
 * Requests that have no registered entry fall back to the default (global) workspace,
 * preserving existing {@code /api/agent/**} behaviour.
 *
 * <p>For GitHub-repo sandboxes the registry also records the {@code owner/repo} the workspace was
 * cloned from, so the agent loop can inject repository-specific context (see
 * {@code Prompts.githubRepoContext}) on every request bound to that sandbox.
 */
@Component
public class WorkspaceRegistry {

    private final Workspace defaultWorkspace;
    private final ConcurrentHashMap<String, Workspace> bySessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> githubRepoBySessionId = new ConcurrentHashMap<>();

    public WorkspaceRegistry(Workspace defaultWorkspace) {
        this.defaultWorkspace = defaultWorkspace;
    }

    /** Returns the workspace registered for {@code sessionId}, or the default when absent/null. */
    public Workspace resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return defaultWorkspace;
        }
        return bySessionId.getOrDefault(sessionId, defaultWorkspace);
    }

    public void register(String sessionId, Workspace workspace) {
        bySessionId.put(sessionId, workspace);
    }

    /**
     * Whether an explicit (non-default) workspace is registered for {@code sessionId}. Used to grant
     * filesystem/shell tools to host-backed agent sessions that have a registered workspace but no
     * Docker sandbox.
     */
    public boolean isRegistered(String sessionId) {
        return sessionId != null && !sessionId.isBlank() && bySessionId.containsKey(sessionId);
    }

    /**
     * Records the {@code owner/repo} a GitHub-repo sandbox was cloned from, so requests bound to this
     * session can be given repository-specific system context.
     */
    public void registerGithubRepo(String sessionId, String repoFullName) {
        if (sessionId == null || sessionId.isBlank() || repoFullName == null || repoFullName.isBlank()) {
            return;
        }
        githubRepoBySessionId.put(sessionId, repoFullName);
    }

    /** Returns the GitHub {@code owner/repo} registered for {@code sessionId}, when this is a repo sandbox. */
    public Optional<String> githubRepo(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(githubRepoBySessionId.get(sessionId));
    }

    public void unregister(String sessionId) {
        bySessionId.remove(sessionId);
        githubRepoBySessionId.remove(sessionId);
    }
}
