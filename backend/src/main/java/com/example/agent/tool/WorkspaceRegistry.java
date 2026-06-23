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

    /**
     * Optional hook that restores a persisted workspace whose in-memory registration was lost (e.g.
     * after a restart). Wired in by the integration module after construction to avoid a dependency
     * cycle; {@code null} when no implementation is present.
     */
    private volatile WorkspaceRehydrator rehydrator;

    public WorkspaceRegistry(Workspace defaultWorkspace) {
        this.defaultWorkspace = defaultWorkspace;
    }

    /** Registers the hook used to lazily restore persisted workspaces on a cache miss. */
    public void setRehydrator(WorkspaceRehydrator rehydrator) {
        this.rehydrator = rehydrator;
    }

    /** Returns the workspace registered for {@code sessionId}, or the default when absent/null. */
    public Workspace resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return defaultWorkspace;
        }
        Workspace workspace = bySessionId.get(sessionId);
        if (workspace == null && rehydrate(sessionId)) {
            workspace = bySessionId.get(sessionId);
        }
        return workspace == null ? defaultWorkspace : workspace;
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
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (bySessionId.containsKey(sessionId)) {
            return true;
        }
        return rehydrate(sessionId) && bySessionId.containsKey(sessionId);
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
        String repo = githubRepoBySessionId.get(sessionId);
        if (repo == null && rehydrate(sessionId)) {
            repo = githubRepoBySessionId.get(sessionId);
        }
        return Optional.ofNullable(repo);
    }

    public void unregister(String sessionId) {
        bySessionId.remove(sessionId);
        githubRepoBySessionId.remove(sessionId);
    }

    /**
     * Asks the rehydrator (when present) to restore {@code sessionId}. No-ops to {@code false} when no
     * rehydrator is wired or an entry already exists, so callers can use it as a cheap miss handler.
     */
    private boolean rehydrate(String sessionId) {
        WorkspaceRehydrator hook = rehydrator;
        if (hook == null || bySessionId.containsKey(sessionId)) {
            return bySessionId.containsKey(sessionId);
        }
        return hook.rehydrate(sessionId);
    }
}
