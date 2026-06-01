package com.example.agent;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatRequest;
import com.example.agent.model.ChatResponse;
import com.example.agent.model.ChatStreamChunk;
import com.example.agent.model.ReasoningConfig;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Thin HTTP wrapper around any OpenAI-schema chat-completions endpoint
 * ({@code POST {base-url}/chat/completions}).
 *
 * <p>The active provider (Ollama, OpenRouter, etc.) and its base URL / API key come from
 * {@link LlmProperties}. When the provider supplies an API key it is sent as an
 * {@code Authorization: Bearer} header; otherwise no auth header is added.
 *
 * <p>{@link #chat} returns the full response in one shot; {@link #chatStream} consumes a
 * Server-Sent Events stream ({@code stream: true}) and emits assistant text token-by-token.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final RestClient restClient;
    private final URI endpoint;
    private final ObjectMapper objectMapper;
    private final HttpClient streamingHttpClient;
    private final String apiKey;

    public OpenAiClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        LlmProperties.Provider provider = properties.activeProvider();
        String baseUrl = provider.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "llm.providers." + properties.provider() + ".base-url must be set");
        }
        this.endpoint = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
        this.apiKey = provider.hasApiKey() ? provider.apiKey() : null;

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        // Remote providers (e.g. OpenRouter) can be slower than a local Ollama; the overall
        // agent loop is still bounded by agent.request-timeout.
        factory.setReadTimeout(Duration.ofSeconds(120));

        var builder = RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(new LoggingInterceptor());

        if (provider.hasApiKey()) {
            builder.requestInterceptor(new BearerAuthInterceptor(provider.apiKey()));
        }

        this.restClient = builder.build();

        // Separate client for streaming: no read timeout (SSE streams are long-lived) and no
        // buffering interceptor, so chunks can be processed as they arrive.
        this.streamingHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("OpenAiClient configured: provider={}, endpoint={}, auth={}",
                properties.provider(), endpoint, provider.hasApiKey() ? "bearer" : "none");
    }

    /**
     * Sends a chat-completions request and returns the parsed response.
     *
     * @param model    model name (provider-specific, e.g. {@code llama3.2} or {@code deepseek/deepseek-chat})
     * @param messages conversation history
     * @param tools    tool definitions to advertise; pass an empty list to omit tool calling
     */
    public ChatResponse chat(String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
        boolean hasTools = tools != null && !tools.isEmpty();
        ChatRequest request = new ChatRequest(
                model,
                messages,
                hasTools ? tools : null,
                hasTools ? "auto" : null,
                ReasoningConfig.maxEffort(),
                false
        );

        log.debug("Sending chat request: model={}, messages={}, tools={}",
                model, messages.size(), hasTools ? tools.size() : 0);

        return withRetry(() -> restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class));
    }

    /**
     * Streams a chat-completions request ({@code stream: true}), invoking {@code onContentDelta}
     * for each chunk of assistant text as it arrives, and returns the fully assembled response
     * (content joined, tool-call argument fragments concatenated) once the stream ends.
     *
     * @param model          model name
     * @param messages       conversation history
     * @param tools          tool definitions to advertise; pass an empty list to omit tool calling
     * @param onContentDelta receives each streamed text fragment in order
     */
    public ChatResponse chatStream(String model, List<ChatMessage> messages,
                                   List<ToolDefinition> tools, Consumer<String> onContentDelta) {
        return chatStream(model, messages, tools, onContentDelta, delta -> {});
    }

    public ChatResponse chatStream(String model, List<ChatMessage> messages,
                                   List<ToolDefinition> tools,
                                   Consumer<String> onContentDelta,
                                   Consumer<String> onReasoningDelta) {
        boolean hasTools = tools != null && !tools.isEmpty();
        ChatRequest request = new ChatRequest(
                model,
                messages,
                hasTools ? tools : null,
                hasTools ? "auto" : null,
                ReasoningConfig.maxEffort(),
                true
        );

        log.debug("Sending streaming chat request: model={}, messages={}, tools={}",
                model, messages.size(), hasTools ? tools.size() : 0);

        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize chat request", e);
        }
        log.debug("→ LLM stream POST {}\n{}", endpoint, body);

        Exception lastErr = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_DELAYS_MS[attempt - 1];
                log.warn("Rate limited (429), retrying stream in {}ms (attempt {}/{})", delay, attempt, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during retry backoff", ie);
                }
            }
            // Build a fresh request each attempt so the BodyPublisher is not reused across retries.
            var httpRequestBuilder = java.net.http.HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null) {
                httpRequestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            try {
                HttpResponse<InputStream> response = streamingHttpClient.send(
                        httpRequestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    lastErr = new IllegalStateException(
                            "LLM streaming request rate limited (HTTP 429): " + errorBody);
                    continue;
                }
                if (response.statusCode() >= 400) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException(
                            "LLM streaming request failed (HTTP " + response.statusCode() + "): " + errorBody);
                }
                log.debug("← LLM stream POST {} status={}", endpoint, response.statusCode());

                return parseStream(response.body(), onContentDelta, onReasoningDelta);
            } catch (IllegalStateException e) {
                throw e;
            } catch (IOException e) {
                throw new IllegalStateException("LLM streaming request failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LLM streaming request interrupted", e);
            }
        }
        throw new IllegalStateException("Rate limited: max streaming retries exceeded", lastErr);
    }

    private ChatResponse parseStream(InputStream stream, Consumer<String> onContentDelta,
                                     Consumer<String> onReasoningDelta) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        // Tool calls are keyed by their stream index so fragments can be merged in order.
        Map<Integer, MutableToolCall> toolCalls = new LinkedHashMap<>();
        String finishReason = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue; // ignore comments, event: lines, and blank separators
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                ChatStreamChunk chunk;
                try {
                    chunk = objectMapper.readValue(data, ChatStreamChunk.class);
                } catch (IOException e) {
                    log.warn("Skipping unparseable stream chunk: {}", e.getMessage());
                    continue;
                }
                if (chunk.choices() == null || chunk.choices().isEmpty()) {
                    continue;
                }

                ChatStreamChunk.Choice choice = chunk.choices().get(0);
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }
                ChatStreamChunk.Delta delta = choice.delta();
                if (delta == null) {
                    continue;
                }
                if (delta.content() != null && !delta.content().isEmpty()) {
                    content.append(delta.content());
                    onContentDelta.accept(delta.content());
                }
                if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                    reasoningContent.append(delta.reasoningContent());
                    onReasoningDelta.accept(delta.reasoningContent());
                }
                if (delta.toolCalls() != null) {
                    for (ChatStreamChunk.ToolCallDelta tc : delta.toolCalls()) {
                        toolCalls.computeIfAbsent(tc.index(), i -> new MutableToolCall()).merge(tc);
                    }
                }
            }
        }

        List<ToolCall> assembledToolCalls = new ArrayList<>();
        for (MutableToolCall tc : toolCalls.values()) {
            assembledToolCalls.add(tc.toToolCall());
        }

        ChatMessage message = new ChatMessage(
                "assistant",
                content.isEmpty() ? null : content.toString(),
                reasoningContent.isEmpty() ? null : reasoningContent.toString(),
                assembledToolCalls.isEmpty() ? null : assembledToolCalls,
                null);
        ChatResponse assembled = new ChatResponse(null, List.of(new ChatResponse.Choice(0, message, finishReason)));
        try {
            log.debug("← LLM stream assembled response:\n{}", objectMapper.writeValueAsString(assembled));
        } catch (IOException e) {
            log.debug("← LLM stream assembled response (serialization failed): {}", e.getMessage());
        }
        return assembled;
    }

    // -------------------------------------------------------------------------
    // Retry helpers
    // -------------------------------------------------------------------------

    private static final int MAX_RETRIES = 4;
    private static final long[] RETRY_DELAYS_MS = {2000, 4000, 8000, 16000};

    /**
     * Wraps an LLM request in retry logic with exponential backoff.
     * Retries only on 429 (rate limit) responses.
     */
    private <T> T withRetry(Callable<T> action) {
        RuntimeException lastErr = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_DELAYS_MS[attempt - 1];
                log.warn("Rate limited (429), retrying in {}ms (attempt {}/{})", delay, attempt, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during retry backoff", ie);
                }
            }
            try {
                return action.call();
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_RETRIES) {
                    lastErr = e;
                } else {
                    throw e;
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("LLM request failed: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("Rate limited: max retries exceeded", lastErr);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Accumulates streamed fragments of a single tool call into a complete {@link ToolCall}. */
    private static final class MutableToolCall {
        private String id;
        private String type = "function";
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(ChatStreamChunk.ToolCallDelta delta) {
            if (delta.id() != null) {
                id = delta.id();
            }
            if (delta.type() != null) {
                type = delta.type();
            }
            if (delta.function() != null) {
                if (delta.function().name() != null) {
                    name = delta.function().name();
                }
                if (delta.function().arguments() != null) {
                    arguments.append(delta.function().arguments());
                }
            }
        }

        ToolCall toToolCall() {
            return new ToolCall(id, type, new ToolCall.FunctionCall(name, arguments.toString()));
        }
    }

    /** Adds {@code Authorization: Bearer <key>} to every request (OpenRouter, OpenAI, etc.). */
    private record BearerAuthInterceptor(String apiKey) implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            request.getHeaders().setBearerAuth(apiKey);
            return execution.execute(request, body);
        }
    }

    private static class LoggingInterceptor implements ClientHttpRequestInterceptor {

        private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            if (log.isDebugEnabled()) {
                log.debug("→ LLM {} {}\n{}", request.getMethod(), request.getURI(),
                        new String(body, StandardCharsets.UTF_8));
            }
            ClientHttpResponse response = execution.execute(request, body);
            byte[] responseBody = response.getBody().readAllBytes();
            if (log.isDebugEnabled()) {
                log.debug("← LLM {} {}\n{}", request.getMethod(), response.getStatusCode(),
                        new String(responseBody, StandardCharsets.UTF_8));
            }
            return new BufferedClientHttpResponse(response, responseBody);
        }
    }

    private record BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body)
            implements ClientHttpResponse {

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
