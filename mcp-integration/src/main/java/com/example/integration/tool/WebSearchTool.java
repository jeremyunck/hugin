package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 1_000;

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public WebSearchTool(
            @Value("${OPEN_ROUTER_API_KEY:}") String apiKey,
            @Value("${web.search.endpoint:https://openrouter.ai/api/v1/chat/completions}") String endpoint,
            @Value("${web.search.model:perplexity/sonar}") String model,
            ObjectMapper objectMapper) {
        this(apiKey, endpoint, model, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    WebSearchTool(String apiKey, String endpoint, String model, ObjectMapper objectMapper, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
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
        if (apiKey == null || apiKey.isBlank()) {
            return "web_search is unavailable: OPEN_ROUTER_API_KEY is not set.";
        }

        String query = requiredString(arguments, "query");
        // Keep the formatting instruction in a system message, separate from the user-supplied
        // query, so the query is treated as data to search for rather than instructions to follow.
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", "Search the web for the latest information about the user's query. "
                                        + "Return a concise summary of the most relevant and recent results, "
                                        + "and include the direct source URL for each fact you report."),
                        Map.of(
                                "role", "user",
                                "content", query)),
                "max_tokens", 1024));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        IOException lastNetworkError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode message = root.path("choices").path(0).path("message");
                    JsonNode content = message.path("content");
                    if (content.isMissingNode() || content.isNull()) {
                        log.warn("OpenRouter search: missing content field in 200 response: {}", response.body());
                        return "Search failed: unexpected response structure (missing content field)";
                    }
                    return content.asText() + formatSources(root, message);
                }

                if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("OpenRouter search returned {} on attempt {}; retrying in {}ms", status, attempt, delay);
                    Thread.sleep(delay);
                    continue;
                }

                log.warn("OpenRouter search API returned {}: {}", status, response.body());
                return "Search failed: OpenRouter API error " + status + ": " + response.body();

            } catch (IOException e) {
                lastNetworkError = e;
                if (attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("OpenRouter search network error on attempt {}; retrying in {}ms: {}", attempt, delay, e.getMessage());
                    Thread.sleep(delay);
                }
            }
        }

        throw new IOException("OpenRouter search failed after " + MAX_ATTEMPTS + " attempts", lastNetworkError);
    }

    /**
     * Builds a "Sources" list from the citation URLs the search model returns alongside the prose.
     * Perplexity/OpenRouter put the actual links in separate fields — root {@code citations} /
     * {@code search_results} and {@code message.annotations[].url_citation} — never inside
     * {@code content}, which only carries bracketed markers like {@code [1]}. Without surfacing
     * these the agent has no URL to hand back when asked for a direct link. Returns an empty string
     * when no citations are present so behaviour is unchanged for models that don't supply them.
     */
    private String formatSources(JsonNode root, JsonNode message) {
        // Preserve order and de-duplicate by URL; keep the first title we see for each.
        Map<String, String> sources = new LinkedHashMap<>();

        // Perplexity native: root-level "citations" is an array of URL strings.
        for (JsonNode c : root.path("citations")) {
            if (c.isTextual()) {
                sources.putIfAbsent(c.asText(), "");
            }
        }
        // Perplexity native: root-level "search_results" carries {url, title}.
        for (JsonNode r : root.path("search_results")) {
            addSource(sources, r.path("url"), r.path("title"));
        }
        // OpenRouter normalized: message.annotations[] of type "url_citation".
        for (JsonNode a : message.path("annotations")) {
            if ("url_citation".equals(a.path("type").asText())) {
                JsonNode citation = a.path("url_citation");
                addSource(sources, citation.path("url"), citation.path("title"));
            }
        }

        if (sources.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\nSources:");
        int i = 1;
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            sb.append("\n[").append(i++).append("] ");
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                sb.append(entry.getValue()).append(" - ");
            }
            sb.append(entry.getKey());
        }
        return sb.toString();
    }

    private void addSource(Map<String, String> sources, JsonNode url, JsonNode title) {
        if (url.isTextual() && !url.asText().isBlank()) {
            sources.putIfAbsent(url.asText(), title.isTextual() ? title.asText() : "");
        }
    }
}
