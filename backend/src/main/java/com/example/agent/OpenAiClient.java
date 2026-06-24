package com.example.agent;

import com.example.agent.model.ChatAttachment;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatRequest;
import com.example.agent.model.ChatResponse;
import com.example.agent.model.ChatStreamChunk;
import com.example.agent.model.ReasoningConfig;
import com.example.agent.model.ThinkingConfig;
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
    private final String reasoningEffort;
    private final boolean deepSeekCompat;

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
        this.reasoningEffort = properties.reasoningEffort();
        this.deepSeekCompat = isDeepSeekEndpoint(baseUrl);

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
    public ChatResponse chat(String model, String reasoningEffortOverride, List<ChatMessage> messages, List<ToolDefinition> tools) {
        boolean hasTools = tools != null && !tools.isEmpty();
        ChatRequest request = buildRequest(model, reasoningEffortOverride, messages, tools, false);

        log.debug("Sending chat request: model={}, messages={}, tools={}",
                model, messages.size(), hasTools ? tools.size() : 0);

        return withRetry(() -> restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class));
    }

    public ChatResponse chat(String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
        return chat(model, null, messages, tools);
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
    public ChatResponse chatStream(String model, String reasoningEffortOverride, List<ChatMessage> messages,
                                   List<ToolDefinition> tools, Consumer<String> onContentDelta) {
        return chatStream(model, reasoningEffortOverride, messages, tools, onContentDelta, delta -> {});
    }

    public ChatResponse chatStream(String model, List<ChatMessage> messages,
                                   List<ToolDefinition> tools, Consumer<String> onContentDelta) {
        return chatStream(model, null, messages, tools, onContentDelta, delta -> {});
    }

    public ChatResponse chatStream(String model, String reasoningEffortOverride, List<ChatMessage> messages,
                                   List<ToolDefinition> tools,
                                   Consumer<String> onContentDelta,
                                   Consumer<String> onReasoningDelta) {
        boolean hasTools = tools != null && !tools.isEmpty();
        ChatRequest request = buildRequest(model, reasoningEffortOverride, messages, tools, true);

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
                log.warn("Transient LLM streaming error, reconnecting in {}ms (attempt {}/{}): {}",
                        delay, attempt, MAX_RETRIES, lastErr != null ? lastErr.getMessage() : "unknown");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during retry backoff", ie);
                }
            }
            // Tracks whether this attempt has already delivered any output to the caller. Once a
            // token has been emitted we must NOT reconnect on a mid-stream drop, because replaying
            // the request from scratch would duplicate everything streamed so far.
            boolean[] emitted = {false};
            Consumer<String> contentTracker = delta -> {
                emitted[0] = true;
                onContentDelta.accept(delta);
            };
            Consumer<String> reasoningTracker = delta -> {
                emitted[0] = true;
                onReasoningDelta.accept(delta);
            };
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

                int status = response.statusCode();
                if (isRetryableStatus(status) && attempt < MAX_RETRIES) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    lastErr = new IllegalStateException(
                            "LLM streaming request failed (HTTP " + status + "): " + errorBody);
                    continue;
                }
                if (status >= 400) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException(
                            "LLM streaming request failed (HTTP " + status + "): " + errorBody);
                }
                log.debug("← LLM stream POST {} status={}", endpoint, status);

                return parseStream(response.body(), contentTracker, reasoningTracker);
            } catch (IllegalStateException e) {
                throw e;
            } catch (IOException e) {
                // A connection failure. If it happened before any output was delivered we can safely
                // reconnect; once tokens have been emitted, replaying would duplicate them, so fail.
                if (emitted[0]) {
                    throw new IllegalStateException(
                            "LLM streaming connection lost after partial response: " + e.getMessage(), e);
                }
                if (attempt < MAX_RETRIES) {
                    lastErr = e;
                    continue;
                }
                throw new IllegalStateException("LLM streaming request failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LLM streaming request interrupted", e);
            }
        }
        throw new IllegalStateException("LLM streaming request failed: max retries exceeded", lastErr);
    }

    public ChatResponse chatStream(String model, List<ChatMessage> messages,
                                   List<ToolDefinition> tools,
                                   Consumer<String> onContentDelta,
                                   Consumer<String> onReasoningDelta) {
        return chatStream(model, null, messages, tools, onContentDelta, onReasoningDelta);
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
                String reasoningDelta = delta.reasoningText();
                if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                    reasoningContent.append(reasoningDelta);
                    onReasoningDelta.accept(reasoningDelta);
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
                null,
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
     * Wraps an LLM request in retry logic with exponential backoff. Retries transient failures:
     * a rate limit (429), a 5xx upstream error, or a lost/refused connection
     * ({@link org.springframework.web.client.ResourceAccessException}). Non-transient errors
     * (4xx other than 429) propagate immediately. Safe because the non-streaming request is
     * replayed in full each attempt.
     */
    private <T> T withRetry(Callable<T> action) {
        RuntimeException lastErr = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_DELAYS_MS[attempt - 1];
                log.warn("Transient LLM error, retrying in {}ms (attempt {}/{}): {}",
                        delay, attempt, MAX_RETRIES, lastErr != null ? lastErr.getMessage() : "unknown");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during retry backoff", ie);
                }
            }
            try {
                return action.call();
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                // 429 (rate limit) and 5xx (transient upstream) are retryable; other 4xx are not.
                if (isRetryableStatus(e.getStatusCode().value()) && attempt < MAX_RETRIES) {
                    lastErr = e;
                } else {
                    throw e;
                }
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // I/O error talking to the provider (connection reset, refused, read timeout, …).
                if (attempt < MAX_RETRIES) {
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
        throw new IllegalStateException("LLM request failed: max retries exceeded", lastErr);
    }

    /** Whether an HTTP status from the LLM endpoint is worth retrying: rate limit or 5xx. */
    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private ChatRequest buildRequest(String model, String reasoningEffortOverride, List<ChatMessage> messages, List<ToolDefinition> tools,
                                     boolean stream) {
        boolean hasTools = tools != null && !tools.isEmpty();
        boolean deepSeek = deepSeekCompat || isDeepSeekModel(model);
        String selectedReasoningEffort = reasoningEffortOverride == null || reasoningEffortOverride.isBlank()
                ? reasoningEffort
                : reasoningEffortOverride;
        List<ChatMessage> normalizedMessages = deepSeek ? normalizeForDeepSeek(messages) : messages;
        List<Map<String, Object>> wireMessages = toWireMessages(normalizedMessages);
        return new ChatRequest(
                model,
                wireMessages,
                hasTools ? tools : null,
                deepSeek ? null : (hasTools ? "auto" : null),
                deepSeek ? selectedReasoningEffort : null,
                deepSeek ? null : ReasoningConfig.withEffort(selectedReasoningEffort),
                deepSeek ? ThinkingConfig.enabled() : null,
                stream
        );
    }

    private static boolean isDeepSeekModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("deepseek-v4") || lower.contains("deepseek-reasoner");
    }

    private static boolean isDeepSeekEndpoint(String baseUrl) {
        try {
            URI uri = URI.create(stripTrailingSlash(baseUrl));
            String host = uri.getHost();
            return host != null && host.toLowerCase(java.util.Locale.ROOT).contains("deepseek.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static List<ChatMessage> normalizeForDeepSeek(List<ChatMessage> messages) {
        List<ChatMessage> normalized = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                normalized.add(new ChatMessage(
                        message.role(),
                        message.content() == null ? "" : message.content(),
                        message.attachments(),
                        message.reasoningContent() == null ? "" : message.reasoningContent(),
                        message.toolCalls(),
                        message.toolCallId()));
            } else {
                normalized.add(message);
            }
        }
        return normalized;
    }

    private static List<Map<String, Object>> toWireMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> wireMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", message.role());
            wire.put("content", toWireContent(message));
            if (message.reasoningContent() != null) {
                wire.put("reasoning_content", message.reasoningContent());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                wire.put("tool_calls", message.toolCalls());
            }
            if (message.toolCallId() != null) {
                wire.put("tool_call_id", message.toolCallId());
            }
            wireMessages.add(wire);
        }
        return wireMessages;
    }

    private static Object toWireContent(ChatMessage message) {
        if (!"user".equals(message.role()) || message.attachments() == null || message.attachments().isEmpty()) {
            return message.content();
        }
        // The agent's chat model is typically text-only (e.g. openai/gpt-oss-120b), and OpenAI-schema
        // providers reject image input for such models. Image understanding is routed through the
        // describe_image tool — which reads the attachment from the per-request context and forwards
        // it to a vision-capable model — so we never send raw image bytes to the chat model here.
        // Instead we note that an image is attached so the model knows to call the tool.
        List<ChatAttachment> images = message.attachments().stream()
                .filter(attachment -> attachment != null
                        && attachment.dataUrl() != null
                        && !attachment.dataUrl().isBlank()
                        && (attachment.mimeType() == null
                            || attachment.mimeType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")))
                .toList();
        if (images.isEmpty()) {
            return message.content();
        }
        StringBuilder content = new StringBuilder();
        if (message.content() != null && !message.content().isBlank()) {
            content.append(message.content());
        }
        for (ChatAttachment image : images) {
            if (content.length() > 0) {
                content.append('\n');
            }
            String name = image.name() == null || image.name().isBlank() ? "image" : image.name();
            content.append("[Attached image: ").append(name)
                    .append(" — not shown here; call the describe_image tool to view it.]");
        }
        return content.toString();
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
            // When debug logging is off (the normal case) skip body buffering entirely so the
            // RestClient can deserialize the response stream directly — buffering the whole LLM
            // response into a byte[] only to discard it added latency and memory churn per request.
            if (!log.isDebugEnabled()) {
                return execution.execute(request, body);
            }
            log.debug("→ LLM {} {}\n{}", request.getMethod(), request.getURI(),
                    new String(body, StandardCharsets.UTF_8));
            ClientHttpResponse response = execution.execute(request, body);
            byte[] responseBody = response.getBody().readAllBytes();
            log.debug("← LLM {} {}\n{}", request.getMethod(), response.getStatusCode(),
                    new String(responseBody, StandardCharsets.UTF_8));
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
