package com.example.agent;

import com.example.agent.model.MemoryRecord;

import java.util.List;

/**
 * Storage backend for long-term memory. Kept in {@code agent-core} as an interface so the agent
 * logic stays decoupled from the concrete store; the Redis implementation lives in the backend
 * module.
 */
public interface MemoryStore {

    /** Persists a memory record. */
    default void save(MemoryRecord record) {
        save("global", record);
    }

    /** Persists a memory record in the namespace for {@code owner}. */
    void save(String owner, MemoryRecord record);

    /**
     * Returns up to {@code topK} stored memories most similar to {@code queryEmbedding}, ordered by
     * descending similarity, excluding any below {@code minScore}.
     */
    default List<ScoredMemory> search(float[] queryEmbedding, int topK, double minScore) {
        return search("global", queryEmbedding, topK, minScore);
    }

    /** Returns up to {@code topK} matches for {@code owner}. */
    List<ScoredMemory> search(String owner, float[] queryEmbedding, int topK, double minScore);

    /** A stored memory paired with its similarity to a query. */
    record ScoredMemory(MemoryRecord record, double score) {}
}
