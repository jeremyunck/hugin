package com.example.agent.tool;

import com.example.agent.MemoryService;
import com.example.agent.MemoryStore;
import com.example.agent.model.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecallMemoryToolTest {

    private final MemoryService memory = mock(MemoryService.class);
    private final RecallMemoryTool tool = new RecallMemoryTool(memory);

    @Test
    void formatsRecalledMemoriesWithScores() {
        when(memory.recall("weather", 5)).thenReturn(List.of(new MemoryStore.ScoredMemory(
                new MemoryRecord("1", "User: hi\nAssistant: hello", new float[]{0.1f}, Instant.now()),
                0.912)));

        String out = tool.execute(Map.of("query", "weather"), new ToolContext(null));

        assertThat(out).contains("User: hi").contains("Assistant: hello").contains("0.91");
    }

    @Test
    void reportsWhenNothingRelevantFound() {
        when(memory.recall("nope", 5)).thenReturn(List.of());
        assertThat(tool.execute(Map.of("query", "nope"), new ToolContext(null)))
                .contains("No relevant memories");
    }

    @Test
    void capsRequestedLimitAtMax() {
        when(memory.recall("q", 20)).thenReturn(List.of());
        tool.execute(Map.of("query", "q", "limit", 100), new ToolContext(null));
        verify(memory).recall("q", 20);
    }
}
