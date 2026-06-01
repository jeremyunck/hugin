package com.example.discord;

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
 * HTTP client that calls the agent server's {@code /api/agent/stream} SSE endpoint and parses the
 * response, dispatching each event to a {@link Handler}.
 */
@Component
public class DiscordAgentClient {

    private final DiscordProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DiscordAgentClient(DiscordProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Receives streamed agent events in order. */
    public interface Handler {
        void onToken(String text);
        default void onConfig(boolean developerMode) {}
        default void onToolCall(String name, String args) {}
        default void onToolResult(String name, String result) {}
        void onError(String message);
    }

    /**
     * Sends {@code prompt} to the agent and streams the response to {@code handler}. Blocks until
     * the stream completes. {@code sessionId} scopes short-term conversation memory so the server
     * recalls the recent turns of this session.
     */
    public void streamChat(String prompt, String sessionId, Handler handler)
            throws IOException, InterruptedException {
        String model = blankToNull(properties.getModel());
        String requestBody = objectMapper.writeValueAsString(new AgentRequest(prompt, model, sessionId));

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(properties.getServerUrl() + "/api/agent/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-API-Key", properties.hasApiKey() ? properties.getApiKey() : "")
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
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (event != null) {
                            if (dispatch(event, data.toString(), handler)) return;
                        }
                        event = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        String payload = line.substring("data:".length());
                        data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
                    }
                    // other SSE fields (id:, retry:, comments) are ignored
                }
            } catch (IOException e) {
                // Stream closed prematurely (e.g. chunked-transfer EOF before "done" event).
                // Any tokens already dispatched to the handler are still valid — treat as
                // end-of-stream rather than propagating, so the caller sees a normal return.
            }
        }
    }

    /** @return true if the stream is finished */
    private boolean dispatch(String event, String data, Handler handler) {
        JsonNode node;
        try {
            node = data.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(data);
        } catch (IOException e) {
            return false;
        }
        switch (event) {
            case "token" -> handler.onToken(node.path("text").asText(""));
            case "config" -> handler.onConfig(node.path("developerMode").asBoolean(false));
            case "tool" -> handler.onToolCall(node.path("name").asText(""), node.path("args").asText(""));
            case "tool_result" -> handler.onToolResult(node.path("name").asText(""), node.path("result").asText(""));
            case "error" -> { handler.onError(node.path("message").asText("unknown error")); return true; }
            case "done" -> { return true; }
            default -> {
                // Ignore non-user-facing events such as reasoning; Discord only renders visible text.
            }
        }
        return false;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
