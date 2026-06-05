package com.example.integration.service;

import com.example.agent.MemoryProperties;
import com.example.agent.MemoryStore;
import com.example.agent.Vectors;
import com.example.agent.model.MemoryRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed {@link MemoryStore}. Records are stored as JSON in a single Redis hash keyed by
 * {@code memory.key-prefix:records} (field = record id). Similarity search loads the records and
 * ranks them in-process by cosine similarity, so it works against vanilla Redis without RediSearch.
 *
 * <p>Only created when {@code memory.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class RedisMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;
    private final String hashKey;

    public RedisMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper, MemoryProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.hashKey = properties.keyPrefix() + ":records";
    }

    @Override
    public void save(String owner, MemoryRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            redis.opsForHash().put(hashKey(owner), record.id(), json);
            evictIfNeeded(owner);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist memory record: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredMemory> search(String owner, float[] queryEmbedding, int topK, double minScore) {
        Map<Object, Object> entries = redis.opsForHash().entries(hashKey(owner));
        if (entries.isEmpty()) {
            return List.of();
        }
        List<ScoredMemory> scored = new ArrayList<>();
        for (Object value : entries.values()) {
            MemoryRecord record = deserialize((String) value);
            if (record == null) {
                continue;
            }
            double score = Vectors.cosineSimilarity(queryEmbedding, record.embedding());
            if (score >= minScore) {
                scored.add(new ScoredMemory(record, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }

    /** Trims the oldest records once the store grows past {@code memory.max-entries}. */
    private void evictIfNeeded(String owner) {
        String key = hashKey(owner);
        Long size = redis.opsForHash().size(key);
        if (size == null || size <= properties.maxEntries()) {
            return;
        }
        int toRemove = (int) (size - properties.maxEntries());
        List<MemoryRecord> all = new ArrayList<>();
        for (Object value : redis.opsForHash().values(key)) {
            MemoryRecord record = deserialize((String) value);
            if (record != null) {
                all.add(record);
            }
        }
        all.sort(Comparator.comparing(MemoryRecord::createdAt));
        for (int i = 0; i < toRemove && i < all.size(); i++) {
            redis.opsForHash().delete(key, all.get(i).id());
        }
        log.debug("Evicted {} oldest memories (cap={})", toRemove, properties.maxEntries());
    }

    private String hashKey(String owner) {
        return hashKey + ":" + encodeOwner(owner);
    }

    private static String encodeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return "global";
        }
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MemoryRecord deserialize(String json) {
        try {
            return objectMapper.readValue(json, MemoryRecord.class);
        } catch (Exception e) {
            log.warn("Skipping unreadable memory record: {}", e.getMessage());
            return null;
        }
    }
}
