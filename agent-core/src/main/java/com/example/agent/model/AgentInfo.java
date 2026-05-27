package com.example.agent.model;

import java.time.Instant;

/** Metadata for a cloud agent instance (persisted to {@code agent.json} and returned by the API). */
public record AgentInfo(
        String id,
        String repoUrl,
        String branch,
        AgentStatus status,
        Instant createdAt,
        String task,
        String error,
        String prUrl) {

    /** Convenience constructor when there is no error message or PR URL. */
    public AgentInfo(String id, String repoUrl, String branch,
                     AgentStatus status, Instant createdAt, String task) {
        this(id, repoUrl, branch, status, createdAt, task, null, null);
    }

    /** Convenience constructor with an error but no PR URL. */
    public AgentInfo(String id, String repoUrl, String branch,
                     AgentStatus status, Instant createdAt, String task, String error) {
        this(id, repoUrl, branch, status, createdAt, task, error, null);
    }
}
