package com.example.agent.tool;

/**
 * SPI that restores a previously-provisioned workspace whose in-memory registration has been lost
 * (e.g. after a server restart). Consulted by {@link WorkspaceRegistry} on a cache miss so a chat
 * resumed long after it was created still resolves to its cloned-on-disk repository workspace rather
 * than silently falling back to the default (whole-host) workspace.
 *
 * <p>Implementations (in the integration module) load the persisted binding for {@code key}, ensure
 * the clone exists on disk — re-cloning when it was removed — and re-register the workspace and any
 * GitHub repository context with the {@link WorkspaceRegistry}. Returning {@code true} means the
 * registry now holds an entry for {@code key}.
 */
public interface WorkspaceRehydrator {

    /**
     * Attempts to restore and register the workspace for {@code key}.
     *
     * @return {@code true} when an entry for {@code key} is now registered, {@code false} when no
     *         persisted binding exists for it.
     */
    boolean rehydrate(String key);
}
