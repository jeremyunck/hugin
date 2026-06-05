package com.example.agent;

import com.example.agent.model.MemoryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private MemoryStore store;

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(embeddingClient, store,
                new MemoryProperties(true, "agent:memory", 3, 0.75, 1000));
    }

    @Test
    void recallEmbedsQueryAndSearchesStore() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        when(embeddingClient.embed("what is the weather?")).thenReturn(embedding);
        var hit = new MemoryStore.ScoredMemory(
                new MemoryRecord("1", "past", embedding, Instant.now()), 0.9);
        when(store.search("alice", embedding, 3, 0.75)).thenReturn(List.of(hit));

        List<MemoryStore.ScoredMemory> result = memoryService.recall("alice", "what is the weather?");

        assertThat(result).containsExactly(hit);
        verify(store).search("alice", embedding, 3, 0.75);
    }

    @Test
    void recallReturnsEmptyForBlankQueryWithoutCallingEmbedding() {
        assertThat(memoryService.recall("  ")).isEmpty();
        assertThat(memoryService.recall(null)).isEmpty();
        verifyNoInteractions(embeddingClient, store);
    }

    @Test
    void recallSwallowsEmbeddingFailures() {
        when(embeddingClient.embed(any(String.class)))
                .thenThrow(new RuntimeException("embedding endpoint down"));

        assertThat(memoryService.recall("alice", "hello")).isEmpty();
        verify(store, never()).search(any(), anyInt(), anyDouble());
    }

    @Test
    void rememberEmbedsExchangeAndStoresRecord() {
        float[] embedding = {0.4f, 0.5f};
        when(embeddingClient.embed(any(String.class))).thenReturn(embedding);

        memoryService.remember("alice", "hi", "hello there");

        ArgumentCaptor<MemoryRecord> captor = ArgumentCaptor.forClass(MemoryRecord.class);
        verify(store).save(eq("alice"), captor.capture());
        MemoryRecord saved = captor.getValue();
        assertThat(saved.text()).isEqualTo("User: hi\nAssistant: hello there");
        assertThat(saved.embedding()).isEqualTo(embedding);
        assertThat(saved.id()).isNotBlank();
        assertThat(saved.createdAt()).isNotNull();
    }

    @Test
    void rememberSkipsBlankAnswers() {
        memoryService.remember("alice", "hi", "  ");
        memoryService.remember("alice", "hi", null);
        verifyNoInteractions(embeddingClient, store);
    }

    @Test
    void rememberSwallowsStoreFailures() {
        when(embeddingClient.embed(any(String.class))).thenReturn(new float[]{1f});
        // Should not propagate.
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(store).save(eq("alice"), any());

        memoryService.remember("alice", "hi", "answer");

        verify(embeddingClient).embed(eq("User: hi\nAssistant: answer"));
    }
}
