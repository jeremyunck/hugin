package com.example.agent;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatRequest;
import com.example.agent.model.ChatResponse;
import com.example.agent.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP wrapper around Ollama's OpenAI-compatible chat-completions endpoint
 * ({@code POST /v1/chat/completions}).
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OllamaClient(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        log.info("OllamaClient configured with base-url={}", baseUrl);
    }

    /**
     * Sends a chat-completions request and returns the parsed response.
     *
     * @param model    Ollama model name (e.g. {@code llama3.2})
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
                false
        );

        if (log.isDebugEnabled()) {
            try {
                log.debug("LLM request: {}", objectMapper.writeValueAsString(request));
            } catch (Exception e) {
                log.debug("Sending chat request to Ollama: model={}, messages={}, tools={}",
                        model, messages.size(), hasTools ? tools.size() : 0);
            }
        }

        ChatResponse response = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        if (log.isDebugEnabled()) {
            try {
                log.debug("LLM response: {}", objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                log.debug("Received response from Ollama: choices={}",
                        response != null ? response.choices().size() : 0);
            }
        }

        return response;
    }
}
