package com.example.integration.controller;

import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * REST endpoint for the AI agent.
 *
 * <p>Example request:
 * <pre>{@code
 * POST /api/agent/chat
 * { "prompt": "What time is it in Tokyo?", "model": "llama3.2" }
 * }</pre>
 *
 * <p>{@code POST /api/agent/stream} returns the same agent run as a Server-Sent Events stream:
 * {@code token} events carry assistant text as it is generated, {@code tool} / {@code tool_result}
 * events report tool calls, {@code done} signals completion, and {@code error} reports a failure.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final long streamTimeoutMillis;

    public AgentController(AgentService agentService,
                           ObjectMapper objectMapper,
                           ExecutorService agentStreamExecutor,
                           @Value("${agent.request-timeout:5m}") Duration requestTimeout) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.streamExecutor = agentStreamExecutor;
        // Allow a margin beyond the agent's own deadline before the SSE connection is torn down.
        this.streamTimeoutMillis = requestTimeout.plusSeconds(60).toMillis();
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AgentRequest request) {
        AgentResponse response = agentService.chat(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AgentRequest request) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);

        streamExecutor.execute(() -> {
            try {
                agentService.chatStream(request, new AgentStreamListener() {
                    @Override
                    public void onContent(String delta) {
                        send(emitter, "token", Map.of("text", delta));
                    }

                    @Override
                    public void onToolCall(String toolName, String arguments) {
                        send(emitter, "tool", Map.of("name", toolName, "args", arguments));
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        send(emitter, "tool_result", Map.of("name", toolName, "result", result));
                    }
                });
                send(emitter, "done", Map.of());
                emitter.complete();
            } catch (Exception e) {
                log.warn("Agent stream failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                send(emitter, "error", Map.of("message", message));
                emitter.complete();
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            // Write the pre-serialized JSON verbatim as the SSE data line (passing a MediaType
            // here would route the String through Jackson again and double-encode it).
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            // Client disconnected; nothing more we can do for this stream.
            log.debug("Could not send SSE event '{}': {}", event, e.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
