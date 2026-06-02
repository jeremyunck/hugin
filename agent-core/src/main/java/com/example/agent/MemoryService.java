package com.example.agent;

import com.example.agent.model.MemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Long-term memory orchestration: embeds text via {@link EmbeddingClient} and persists/retrieves it
 * through a {@link MemoryStore}. Only created when {@code memory.enabled=true}.
 *
 * <p>Both operations are best-effort: a failing embedding endpoint or store is logged and swallowed
 * so the agent loop keeps working without long-term memory.
 */
@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final EmbeddingClient embeddingClient;
    private final MemoryStore store;
    private final MemoryProperties properties;

    public MemoryService(EmbeddingClient embeddingClient, MemoryStore store, MemoryProperties properties) {
        this.embeddingClient = embeddingClient;
        this.store = store;
        this.properties = properties;
    }

    /** Embeds {@code query} and returns the most similar stored memories (never throws). */
    public List<MemoryStore.ScoredMemory> recall(String query) {
        return recall(query, properties.topK());
    }

    /**
     * Embeds {@code query} and returns up to {@code topK} most similar stored memories (never throws).
     * Used by the {@code recall_memory} tool so the agent can ask for more or fewer hits on demand.
     */
    public List<MemoryStore.ScoredMemory> recall(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = topK > 0 ? topK : properties.topK();
        try {
            float[] embedding = embeddingClient.embed(query);
            List<MemoryStore.ScoredMemory> hits =
                    store.search(embedding, limit, properties.minScore());
            log.debug("Recalled {} memories (top-k={}, min-score={})",
                    hits.size(), limit, properties.minScore());
            return hits;
        } catch (Exception e) {
            log.warn("Memory recall failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Embeds and persists a prompt/answer exchange for future recall (never throws). */
    public void remember(String userPrompt, String assistantAnswer) {
        if (assistantAnswer == null || assistantAnswer.isBlank()) {
            return;
        }
        try {
            String text = "User: " + (userPrompt == null ? "" : userPrompt)
                    + "\nAssistant: " + assistantAnswer;
            float[] embedding = embeddingClient.embed(text);
            store.save(new MemoryRecord(UUID.randomUUID().toString(), text, embedding, Instant.now()));
            log.debug("Stored memory ({} chars)", text.length());
        } catch (Exception e) {
            log.warn("Memory store failed: {}", e.getMessage());
        }
    }
}
