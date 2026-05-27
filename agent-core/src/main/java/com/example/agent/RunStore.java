package com.example.agent;

import com.example.agent.model.AgentInfo;

import java.util.List;
import java.util.Optional;

/**
 * SPI for durable persistence of cloud-agent run metadata.
 *
 * <p>Implementations (e.g. file-based JSON, Postgres-backed in {@code mcp-integration})
 * are discovered as Spring beans and wired into {@link CloudAgentService}.
 * The store must support startup reload so agent state survives restarts.
 */
public interface RunStore {

    /** Persists a new or updated agent run. */
    void save(AgentInfo info);

    /** Returns all known agent runs. */
    List<AgentInfo> findAll();

    /** Returns a specific agent run, if present. */
    Optional<AgentInfo> findById(String agentId);

    /** Removes an agent run from the store. */
    void deleteById(String agentId);
}