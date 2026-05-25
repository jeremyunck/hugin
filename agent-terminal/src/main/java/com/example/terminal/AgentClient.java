package com.example.terminal;

import com.example.agent.model.AgentRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client that calls the agent server's {@code /api/agent/stream} endpoint and parses the
 * Server-Sent Events response, dispatching each event to a {@link Handler}.
 */
@Component
public class AgentClient {

    private final TerminalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AgentClient(TerminalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Receives streamed agent events in order. */
    public interface Handler {
        /** A chunk of assistant text. */
        void onToken(String text);

        /** The agent is about to run a tool. */
        default void onToolCall(String name, String args) {}

        /** A tool finished; {@code result} is what was fed back to the model. */
        default void onToolResult(String name, String result) {}

        /** The server reported an error mid-stream. */
        void onError(String message);
    }

    /**
     * Sends {@code prompt} to the agent and streams the response to {@code handler}. Blocks until
     * the stream completes.
     *
     * @throws IOException          if the server cannot be reached or the connection drops
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void streamChat(String prompt, String model, Handler handler)
            throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(new AgentRequest(prompt, model));

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.serverUrl() + "/api/agent/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-API-Key", properties.hasApiKey() ? properties.apiKey() : "")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            handler.onError("server returned HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : ": " + body.strip()));
            return;
        }

        parseSse(response.body(), handler);
    }

    private void parseSse(InputStream stream, Handler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null) {
                        boolean done = dispatch(event, data.toString(), handler);
                        if (done) {
                            return;
                        }
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(stripLeadingSpace(line.substring("data:".length())));
                }
                // other SSE fields (id:, retry:, comments) are ignored
            }
        }
    }

    /** @return true if the stream is finished and parsing should stop */
    private boolean dispatch(String event, String data, Handler handler) {
        JsonNode node;
        try {
            node = data.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(data);
        } catch (IOException e) {
            return false; // skip malformed payloads
        }
        switch (event) {
            case "token" -> handler.onToken(node.path("text").asText(""));
            case "tool" -> handler.onToolCall(node.path("name").asText(""), node.path("args").asText(""));
            case "tool_result" ->
                    handler.onToolResult(node.path("name").asText(""), node.path("result").asText(""));
            case "error" -> {
                handler.onError(node.path("message").asText("unknown error"));
                return true;
            }
            case "done" -> {
                return true;
            }
            default -> { /* ignore unknown events */ }
        }
        return false;
    }

    private static String stripLeadingSpace(String s) {
        return s.startsWith(" ") ? s.substring(1) : s;
    }
}
