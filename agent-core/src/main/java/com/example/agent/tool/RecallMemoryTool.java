package com.example.agent.tool;

import com.example.agent.MemoryService;
import com.example.agent.MemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets the agent explicitly search long-term semantic memory for relevant past exchanges.
 *
 * <p>Long-term memories are also recalled automatically against the user's prompt at the start of
 * each request, but this tool lets the agent look things up on demand — e.g. to answer a follow-up
 * about something the user mentioned in an earlier conversation that is not in the current
 * short-term context. Only registered when {@code memory.enabled=true} (it depends on
 * {@link MemoryService}, which is only present then).
 */
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class RecallMemoryTool implements LocalTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final MemoryService memoryService;

    public RecallMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "recall_memory";
    }

    @Override
    public String description() {
        return "Search long-term memory of past conversations for information relevant to a query, "
                + "returning the most semantically similar stored exchanges (each is a 'User: ... / "
                + "Assistant: ...' turn from an earlier conversation). Use this to recall facts, "
                + "preferences, decisions, or context the user shared earlier that is not in the "
                + "current short-term context. Returns nothing when no sufficiently similar memory "
                + "exists.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "What to search memory for, phrased as the topic or "
                                        + "question you want past context about."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Maximum number of memories to return (default "
                                        + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ").")),
                "required", List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return execute(arguments, new ToolContext(null));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String query = requiredString(arguments, "query");
        int limit = optionalInt(arguments, "limit", DEFAULT_LIMIT);
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);

        String owner = ctx != null && ctx.username() != null && !ctx.username().isBlank()
                ? ctx.username()
                : "global";
        List<MemoryStore.ScoredMemory> hits = memoryService.recall(owner, query, limit);
        if (hits.isEmpty()) {
            return "No relevant memories found for: " + query;
        }

        StringBuilder sb = new StringBuilder("Recalled ")
                .append(hits.size())
                .append(hits.size() == 1 ? " memory" : " memories")
                .append(" for \"").append(query).append("\":");
        int n = 1;
        for (MemoryStore.ScoredMemory hit : hits) {
            sb.append("\n\n").append(n++).append(". (similarity ")
                    .append(String.format(Locale.ROOT, "%.2f", hit.score())).append(")\n")
                    .append(hit.record().text());
        }
        return sb.toString();
    }
}
