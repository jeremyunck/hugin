package com.example.integration.tool;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared transport for OpenRouter web-search calls (Perplexity Sonar and friends).
 *
 * <p>Encapsulates the request shape, retry/backoff policy, and citation parsing that both
 * {@link WebSearchTool} (a single query) and {@link DeepResearchTool} (many queries fanned out
 * across a topic) depend on, so the HTTP contract lives in exactly one place. A call returns the
 * model's prose together with the structured list of source URLs it cited — the latter is what lets
 * deep research de-duplicate and curate sources across several searches.
 */
@Component
public class OpenRouterSearchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterSearchClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 1_000;

    private final String apiKey;
    private final String endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenRouterSearchClient(
            @Value("${OPEN_ROUTER_API_KEY:}") String apiKey,
            @Value("${web.search.endpoint:https://openrouter.ai/api/v1/chat/completions}") String endpoint,
            ObjectMapper objectMapper) {
        this(apiKey, endpoint, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenRouterSearchClient(String apiKey, String endpoint, ObjectMapper objectMapper, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** A single cited source returned by the search model. {@code title} may be empty. */
    public record Source(String url, String title) {
    }

    /** The model's prose answer plus the de-duplicated, ordered sources it cited. */
    public record SearchResult(String content, List<Source> sources) {
    }

    /** Thrown for OpenRouter API errors and malformed responses; carries a user-facing message. */
    public static class SearchException extends Exception {
        public SearchException(String message) {
            super(message);
        }
    }

    /** Whether the search backend is configured (an API key is present). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Runs one search against OpenRouter and returns the prose answer and its cited sources.
     *
     * @throws SearchException on a non-retriable API error or a malformed response
     * @throws IOException      when the network call fails after exhausting retries
     */
    public SearchResult search(String model, String systemPrompt, String query, int maxTokens)
            throws SearchException, IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", query)),
                "max_tokens", maxTokens));

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
                        throw new SearchException(
                                "unexpected response structure (missing content field)");
                    }
                    return new SearchResult(content.asText(), parseSources(root, message));
                }

                if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("OpenRouter search returned {} on attempt {}; retrying in {}ms", status, attempt, delay);
                    Thread.sleep(delay);
                    continue;
                }

                log.warn("OpenRouter search API returned {}: {}", status, response.body());
                throw new SearchException("OpenRouter API error " + status + ": " + response.body());

            } catch (IOException e) {
                lastNetworkError = e;
                if (attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("OpenRouter search network error on attempt {}; retrying in {}ms: {}",
                            attempt, delay, e.getMessage());
                    Thread.sleep(delay);
                }
            }
        }

        throw new IOException("OpenRouter search failed after " + MAX_ATTEMPTS + " attempts", lastNetworkError);
    }

    /**
     * Collects the citation URLs the search model returns alongside the prose, preserving order and
     * de-duplicating by URL (keeping the first title seen). Perplexity/OpenRouter put the actual
     * links in separate fields — root {@code citations} / {@code search_results} and
     * {@code message.annotations[].url_citation} — never inside {@code content}, which only carries
     * bracketed markers like {@code [1]}.
     */
    private List<Source> parseSources(JsonNode root, JsonNode message) {
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

        List<Source> result = new ArrayList<>(sources.size());
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            result.add(new Source(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private void addSource(Map<String, String> sources, JsonNode url, JsonNode title) {
        if (url.isTextual() && !url.asText().isBlank()) {
            sources.putIfAbsent(url.asText(), title.isTextual() ? title.asText() : "");
        }
    }
}
