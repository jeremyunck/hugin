package com.example.integration.service;

import com.example.agent.MemoryProperties;
import com.example.agent.MemoryStore.ScoredMemory;
import com.example.agent.model.MemoryRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMemoryStoreTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    HashOperations<String, Object, Object> hashOps;

    ObjectMapper objectMapper;
    RedisMemoryStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        var props = new MemoryProperties(true, "memory", 5, 0.0, 100);
        store = new RedisMemoryStore(redisTemplate, objectMapper, props);
    }

    @Test
    void savePersistsRecordToRedis() {
        var record = new MemoryRecord("id1", "user prompt", new float[]{0.1f, 0.2f}, Instant.now());

        store.save(record);

        verify(hashOps).put(eq(hashKey("global")), eq("id1"), any(String.class));
    }

    @Test
    void savePersistsValidJson() throws Exception {
        var record = new MemoryRecord("id42", "hello world", new float[]{0.5f}, Instant.now());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        store.save(record);

        verify(hashOps).put(eq(hashKey("global")), eq("id42"), jsonCaptor.capture());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("id42");
        assertThat(json).contains("hello world");
    }

    @Test
    void searchReturnsEmptyWhenNoEntries() {
        when(hashOps.entries(hashKey("global"))).thenReturn(Map.of());

        List<ScoredMemory> results = store.search(new float[]{0.1f}, 5, 0.0);

        assertThat(results).isEmpty();
    }

    @Test
    void searchRanksAndFiltersByScore() throws Exception {
        var record = new MemoryRecord("id1", "user", new float[]{1.0f, 0.0f}, Instant.now());
        String json = objectMapper.writeValueAsString(record);
        when(hashOps.entries(hashKey("global"))).thenReturn(Map.of("id1", json));

        // Query with same direction = cosine similarity 1.0
        List<ScoredMemory> results = store.search(new float[]{1.0f, 0.0f}, 5, 0.0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.01));
        assertThat(results.get(0).record().id()).isEqualTo("id1");
    }

    @Test
    void searchFiltersOutLowScores() throws Exception {
        // Record embedding perpendicular to query => cosine similarity = 0.0
        var record = new MemoryRecord("id1", "irrelevant", new float[]{0.0f, 1.0f}, Instant.now());
        String json = objectMapper.writeValueAsString(record);
        when(hashOps.entries(hashKey("global"))).thenReturn(Map.of("id1", json));

        // Require minimum score of 0.5 – perpendicular vectors have similarity 0.0
        List<ScoredMemory> results = store.search(new float[]{1.0f, 0.0f}, 5, 0.5);

        assertThat(results).isEmpty();
    }

    @Test
    void searchReturnsTopKResults() throws Exception {
        var record1 = new MemoryRecord("id1", "text1", new float[]{1.0f, 0.0f}, Instant.now().minusSeconds(10));
        var record2 = new MemoryRecord("id2", "text2", new float[]{0.9f, 0.1f}, Instant.now().minusSeconds(5));
        var record3 = new MemoryRecord("id3", "text3", new float[]{0.8f, 0.2f}, Instant.now());
        String json1 = objectMapper.writeValueAsString(record1);
        String json2 = objectMapper.writeValueAsString(record2);
        String json3 = objectMapper.writeValueAsString(record3);
        when(hashOps.entries(hashKey("global"))).thenReturn(Map.of(
                "id1", json1, "id2", json2, "id3", json3
        ));

        // TopK=2, so only 2 results should be returned
        List<ScoredMemory> results = store.search(new float[]{1.0f, 0.0f}, 2, 0.0);

        assertThat(results).hasSize(2);
        // Results should be sorted descending by score
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
    }

    @Test
    void searchSkipsMalformedJsonEntries() {
        when(hashOps.entries(hashKey("global"))).thenReturn(Map.of(
                "bad", "not-valid-json{{{",
                "also-bad", "null"
        ));

        List<ScoredMemory> results = store.search(new float[]{1.0f}, 5, 0.0);

        // Malformed entries are silently skipped (logged as warning)
        assertThat(results).isEmpty();
    }

    @Test
    void saveEvictsOldestWhenOverCap() throws Exception {
        // Create a store with maxEntries=1
        var props = new MemoryProperties(true, "memory", 5, 0.0, 1);
        var capStore = new RedisMemoryStore(redisTemplate, objectMapper, props);

        // Return size=2 (over cap of 1)
        when(hashOps.size(hashKey("global"))).thenReturn(2L);
        var oldRecord = new MemoryRecord("old-id", "old text", new float[]{0.1f}, Instant.now().minusSeconds(100));
        var newRecord = new MemoryRecord("new-id", "new text", new float[]{0.2f}, Instant.now());
        String oldJson = objectMapper.writeValueAsString(oldRecord);
        String newJson = objectMapper.writeValueAsString(newRecord);
        when(hashOps.values(hashKey("global"))).thenReturn(List.of(oldJson, newJson));

        var recordToSave = new MemoryRecord("save-id", "save text", new float[]{0.3f}, Instant.now());
        capStore.save(recordToSave);

        // Verify the oldest record was deleted
        verify(hashOps).delete(hashKey("global"), "old-id");
    }

    @Test
    void saveDoesNotEvictWhenUnderCap() {
        when(hashOps.size(hashKey("global"))).thenReturn(5L);
        var props = new MemoryProperties(true, "memory", 5, 0.0, 100);
        var capStore = new RedisMemoryStore(redisTemplate, objectMapper, props);

        var record = new MemoryRecord("id1", "text", new float[]{0.1f}, Instant.now());
        capStore.save(record);

        // Delete should never be called when under cap
        verify(hashOps, never()).delete(any(), any());
    }

    @Test
    void searchResultsAreSortedByDescendingScore() throws Exception {
        // Create records with known cosine similarities against query [1, 0]
        // record1: [1, 0] -> similarity 1.0
        // record2: [0.707, 0.707] -> similarity ~0.707
        // record3: [0.5, 0.866] -> similarity 0.5
        var record1 = new MemoryRecord("id1", "best", new float[]{1.0f, 0.0f}, Instant.now());
        var record2 = new MemoryRecord("id2", "medium", new float[]{0.707f, 0.707f}, Instant.now());
        var record3 = new MemoryRecord("id3", "worst", new float[]{0.5f, 0.866f}, Instant.now());
        when(hashOps.entries(hashKey("global"))).thenReturn(Map.of(
                "id1", objectMapper.writeValueAsString(record1),
                "id2", objectMapper.writeValueAsString(record2),
                "id3", objectMapper.writeValueAsString(record3)
        ));

        List<ScoredMemory> results = store.search(new float[]{1.0f, 0.0f}, 5, 0.0);

        assertThat(results).hasSize(3);
        // Verify descending order
        for (int i = 0; i < results.size() - 1; i++) {
            assertThat(results.get(i).score())
                    .isGreaterThanOrEqualTo(results.get(i + 1).score());
        }
    }

    private static String hashKey(String owner) {
        return "memory:records:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString((owner == null || owner.isBlank() ? "global" : owner).getBytes(StandardCharsets.UTF_8));
    }
}
