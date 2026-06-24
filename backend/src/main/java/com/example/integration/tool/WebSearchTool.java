package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool implements LocalTool {

    private static final String SYSTEM_PROMPT = "Search the web for the latest information about the "
            + "user's query. Return a concise summary of the most relevant and recent results, and "
            + "include the direct source URL for each fact you report.";
    private static final int MAX_TOKENS = 1024;

    private final OpenRouterSearchClient searchClient;
    private final String model;

    @Autowired
    public WebSearchTool(
            OpenRouterSearchClient searchClient,
            @Value("${web.search.model:perplexity/sonar}") String model) {
        this.searchClient = searchClient;
        this.model = model;
    }

    @Override
    public boolean isAvailable() {
        // Only advertise web search when the integration is actually set up (API key present),
        // so the model never sees a capability the user has not configured.
        return searchClient.isConfigured();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web for current information, news, and recent events. "
                + "Returns a summary of the most relevant and up-to-date search results.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The search query")),
                "required", List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        if (!searchClient.isConfigured()) {
            return "web_search is unavailable: OPEN_ROUTER_API_KEY is not set.";
        }

        String query = requiredString(arguments, "query");
        // Keep the formatting instruction in a system message, separate from the user-supplied
        // query, so the query is treated as data to search for rather than instructions to follow.
        try {
            OpenRouterSearchClient.SearchResult result =
                    searchClient.search(model, SYSTEM_PROMPT, query, MAX_TOKENS);
            return result.content() + formatSources(result.sources());
        } catch (OpenRouterSearchClient.SearchException e) {
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * Builds a "Sources" list from the citation URLs the search model returns alongside the prose.
     * Returns an empty string when no citations are present so behaviour is unchanged for models that
     * don't supply them.
     */
    private String formatSources(List<OpenRouterSearchClient.Source> sources) {
        if (sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nSources:");
        int i = 1;
        for (OpenRouterSearchClient.Source source : sources) {
            sb.append("\n[").append(i++).append("] ");
            if (source.title() != null && !source.title().isBlank()) {
                sb.append(source.title()).append(" - ");
            }
            sb.append(source.url());
        }
        return sb.toString();
    }
}
