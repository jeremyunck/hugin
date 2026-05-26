package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps cloud-agent session IDs to their per-agent {@link Workspace}.
 * Requests that have no registered entry fall back to the default (global) workspace,
 * preserving existing {@code /api/agent/**} behaviour.
 */
@Component
public class WorkspaceRegistry {

    private final Workspace defaultWorkspace;
    private final ConcurrentHashMap<String, Workspace> bySessionId = new ConcurrentHashMap<>();

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

    public void unregister(String sessionId) {
        bySessionId.remove(sessionId);
    }
}
