package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatResponse;

/**
 * Unit tests for {@link OpenAiClient#chat} and related inner classes, using a local
 * {@link HttpServer} to serve canned JSON responses — the same approach as
 * {@link OpenAiClientStreamTest}.
 */
class OpenAiClientTest {

    private HttpServer server;
    private ch.qos.logback.classic.Level originalLevel;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (originalLevel != null) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OpenAiClient.class))
                    .setLevel(originalLevel);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final String SIMPLE_RESPONSE = """
            {
              "id": "chatcmpl-123",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "Hello there!"},
                  "finish_reason": "stop"
                }
              ]
            }
            """;

    private OpenAiClient clientWithResponse(int statusCode, String responseBody,
                                             String contentType) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider("http://localhost:" + port + "/v1", null)));
        return new OpenAiClient(properties, new ObjectMapper());
    }

    private OpenAiClient clientWithApiKeyAndResponse(String apiKey,
                                                      String[] capturedAuth) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = SIMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider(
                        "http://localhost:" + port + "/v1", apiKey)));
        return new OpenAiClient(properties, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void chatReturnsAssistantContent() throws IOException {
        OpenAiClient client = clientWithResponse(200, SIMPLE_RESPONSE, "application/json");

        ChatResponse response = client.chat("m", List.of(ChatMessage.user("hi")), List.of());

        assertThat(response.choices()).hasSize(1);
        ChatResponse.Choice choice = response.choices().get(0);
        assertThat(choice.message().content()).isEqualTo("Hello there!");
        assertThat(choice.finishReason()).isEqualTo("stop");
    }

    @Test
    void chatSendsBearerAuthHeader() throws IOException {
        String[] capturedAuth = new String[1];
        OpenAiClient client = clientWithApiKeyAndResponse("sk-test-key-abc", capturedAuth);

        client.chat("m", List.of(ChatMessage.user("hello")), List.of());

        assertThat(capturedAuth[0]).isEqualTo("Bearer sk-test-key-abc");
    }

    @Test
    void chatSendsNoBearerAuthHeaderWhenKeyAbsent() throws IOException {
        String[] capturedAuth = new String[1];
        // Use the no-API-key builder path
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = SIMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider(
                        "http://localhost:" + port + "/v1", null)));
        OpenAiClient client = new OpenAiClient(properties, new ObjectMapper());

        client.chat("m", List.of(ChatMessage.user("hello")), List.of());

        assertThat(capturedAuth[0]).isNull();
    }

    @Test
    void chatUsesMediumReasoningByDefault() throws IOException {
        String[] capturedBody = new String[1];
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = SIMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider("http://localhost:" + port + "/v1", null)));
        OpenAiClient client = new OpenAiClient(properties, new ObjectMapper());

        client.chat("m", List.of(ChatMessage.user("hello")), List.of());

        assertThat(capturedBody[0]).contains("\"reasoning\"");
        assertThat(capturedBody[0]).contains("\"effort\":\"medium\"");
    }

    @Test
    void chatUsesConfiguredReasoningEffort() throws IOException {
        String[] capturedBody = new String[1];
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = SIMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "m", "high",
                Map.of("test", new LlmProperties.Provider("http://localhost:" + port + "/v1", null)));
        OpenAiClient client = new OpenAiClient(properties, new ObjectMapper());

        client.chat("m", List.of(ChatMessage.user("hello")), List.of());

        assertThat(capturedBody[0]).contains("\"reasoning\"");
        assertThat(capturedBody[0]).contains("\"effort\":\"high\"");
    }

    @Test
    void chatUsesDeepSeekThinkingParametersAndOmitsToolChoice() throws IOException {
        String[] capturedBody = new String[1];
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = SIMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "deepseek-v4-flash", "medium",
                Map.of("test", new LlmProperties.Provider(
                        "http://localhost:" + port + "/v1", null)));
        OpenAiClient client = new OpenAiClient(properties, new ObjectMapper());

        client.chat("deepseek-v4-flash", List.of(ChatMessage.user("hello")), List.of());

        assertThat(capturedBody[0]).contains("\"thinking\":{\"type\":\"enabled\"}");
        assertThat(capturedBody[0]).contains("\"reasoning_effort\":\"medium\"");
        assertThat(capturedBody[0]).doesNotContain("\"tool_choice\"");
        assertThat(capturedBody[0]).doesNotContain("\"reasoning\":");
    }

    @Test
    void chatLogsDebugRequestAndResponse() throws IOException {
        // Enable DEBUG on the OpenAiClient logger to exercise LoggingInterceptor branches.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OpenAiClient.class);
        originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        OpenAiClient client = clientWithResponse(200, SIMPLE_RESPONSE, "application/json");

        // Simply verify the request completes without exception — the debug branches are covered.
        ChatResponse response = client.chat("m", List.of(ChatMessage.user("debug test")), List.of());
        assertThat(response.choices()).isNotEmpty();
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello there!");
    }

    @Test
    void chatThrowsOnMissingBaseUrl() {
        // Provider with blank base-url should throw at construction time.
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider("", null)));

        assertThatThrownBy(() -> new OpenAiClient(properties, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url must be set");
    }

    @Test
    void chatRetriesOn429ThenSucceeds() throws IOException {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.getAndIncrement();
            if (count == 0) {
                // First request: return 429
                exchange.sendResponseHeaders(429, 0);
                exchange.getResponseBody().close();
            } else {
                // Subsequent request: return valid response
                byte[] body = SIMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        // Use a shorter retry delay by overriding; since we can't override the static field,
        // we just verify the retry actually happened (request count > 1) and that it succeeded.
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider(
                        "http://localhost:" + port + "/v1", null)));
        OpenAiClient client = new OpenAiClient(properties, new ObjectMapper());

        // The first attempt returns 429; withRetry will sleep then retry.
        // To keep the test fast, we accept that this test may take a couple of seconds.
        ChatResponse response = client.chat("m", List.of(ChatMessage.user("retry me")), List.of());

        assertThat(requestCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello there!");
    }

    @Test
    void chatStreamWithBearerAuth() throws IOException {
        // Verify that chatStream also sends the Authorization header.
        String[] capturedAuth = new String[1];
        String sse = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var properties = new LlmProperties("test", "m", "medium",
                Map.of("test", new LlmProperties.Provider(
                        "http://localhost:" + port + "/v1", "sk-stream-key")));
        OpenAiClient client = new OpenAiClient(properties, new ObjectMapper());

        List<String> tokens = new ArrayList<>();
        ChatResponse response = client.chatStream(
                "m", List.of(ChatMessage.user("stream auth test")), List.of(), tokens::add);

        assertThat(capturedAuth[0]).isEqualTo("Bearer sk-stream-key");
        assertThat(response.choices()).isNotEmpty();
        assertThat(response.choices().get(0).message().content()).isEqualTo("ok");
    }
}
