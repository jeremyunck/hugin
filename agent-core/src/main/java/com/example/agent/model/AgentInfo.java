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
        String error) {

    /** Convenience constructor when there is no error message. */
    public AgentInfo(String id, String repoUrl, String branch,
                     AgentStatus status, Instant createdAt, String task) {
        this(id, repoUrl, branch, status, createdAt, task, null);
    }
}
