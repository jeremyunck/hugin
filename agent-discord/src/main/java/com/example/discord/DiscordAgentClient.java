package com.example.discord;

import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client that calls the agent server's {@code /api/agent/chat} endpoint and returns the
 * completed response.
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

    /**
     * Sends {@code prompt} to the agent and returns the completed response text. Blocks until the
     * agent finishes. {@code sessionId} scopes short-term conversation memory so the server recalls
     * the recent turns of this session.
     */
    public String chat(String prompt, String sessionId) throws IOException, InterruptedException {
        String model = blankToNull(properties.getModel());
        String requestBody = objectMapper.writeValueAsString(new AgentRequest(prompt, model, sessionId));

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(properties.getServerUrl() + "/api/agent/chat"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", properties.hasApiKey() ? properties.getApiKey() : "")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() >= 400) {
            throw new IOException("server returned HTTP " + response.statusCode()
                    + (response.body().isBlank() ? "" : ": " + response.body().strip()));
        }

        return objectMapper.readValue(response.body(), AgentResponse.class).response();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
