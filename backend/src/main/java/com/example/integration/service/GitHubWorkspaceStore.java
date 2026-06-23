package com.example.integration.service;

import java.util.Optional;

/**
 * Persists the binding between a GitHub project chat (its {@code sandboxId}) and the repository
 * cloned for it on disk, so the workspace can be rehydrated when the chat is resumed after the
 * in-memory registration was lost (e.g. a server restart).
 */
public interface GitHubWorkspaceStore {

    /** A persisted GitHub project workspace. */
    record Record(String sandboxId, String repoFullName, String branch, String cloneUrl, String workspacePath) {}

    /** Inserts or updates the binding for {@code sandboxId}. */
    void save(Record record);

    /** Returns the persisted binding for {@code sandboxId}, if any. */
    Optional<Record> find(String sandboxId);

    /** Removes the binding for {@code sandboxId} (no-op when absent). */
    void delete(String sandboxId);
}
