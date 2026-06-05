package com.example.integration.controller;

import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.DeveloperModeService;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.integration.agent.UserAgent;
import com.example.integration.agent.UserAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.ExecutorService;

/**
 * REST endpoint for the AI agent.
 *
 * <p>Example request:
 * <pre>{@code
 * POST /api/agent/chat
 * {
 *   "prompt": "What time is it in Tokyo?",
 *   "decision": "llama3.2",
 *   "complex": "openai/gpt-oss-120b",
 *   "simple": "openai/gpt-oss-20b"
 * }
 * }</pre>
 *
 * <p>{@code POST /api/agent/stream} returns the same agent run as a Server-Sent Events stream:
 * {@code token} events carry assistant text as it is generated, {@code reasoning} exposes the
 * model's reasoning stream for clients that want it, {@code tool} / {@code tool_result} events
 * report tool calls, {@code done} signals completion, and {@code error} reports a failure.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final long streamTimeoutMillis;
    private final DeveloperModeService developerModeService;
    private final UserAgentService userAgentService;

    public AgentController(AgentService agentService,
                           ObjectMapper objectMapper,
                           ExecutorService agentStreamExecutor,
                           DeveloperModeService developerModeService,
                           UserAgentService userAgentService,
                           @Value("${agent.request-timeout:5m}") Duration requestTimeout) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.streamExecutor = agentStreamExecutor;
        this.developerModeService = developerModeService;
        this.userAgentService = userAgentService;
        // Allow a margin beyond the agent's own deadline before the SSE connection is torn down.
        this.streamTimeoutMillis = requestTimeout.plusSeconds(60).toMillis();
    }

    @GetMapping("/tools")
    public List<AgentService.ToolSummary> listTools() {
        return agentService.availableTools();
    }

    public ResponseEntity<?> chat(@RequestBody AgentRequest request) {
        return chat(request, null);
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AgentRequest request, @AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        AgentRequest scoped = scopedRequest(request, owner);
        AgentResponse response = agentService.chat(scoped, memoryOwner(owner, scoped.agentId()));
        return ResponseEntity.ok(response);
    }

    public SseEmitter chatStream(@RequestBody AgentRequest request) {
        return chatStream(request, null);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AgentRequest request, @AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        AgentRequest scoped = scopedRequest(request, owner);
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);

        streamExecutor.execute(() -> {
            send(emitter, "config", Map.of("developerMode", developerModeService.isEnabled()));
            try {
                StringBuilder streamedContent = new StringBuilder();
                AgentResponse response = agentService.chatStream(scoped, new AgentStreamListener() {
                    @Override
                    public void onConfig(boolean developerMode) {
                        send(emitter, "config", Map.of("developerMode", developerMode));
                    }

                    @Override
                    public void onContent(String delta) {
                        streamedContent.append(delta);
                        send(emitter, "token", Map.of("text", delta));
                    }

                    @Override
                    public void onReasoning(String delta) {
                        send(emitter, "reasoning", Map.of("text", delta));
                    }

                    @Override
                    public void onToolCall(String toolName, String arguments) {
                        send(emitter, "tool", Map.of("name", toolName, "args", arguments));
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        send(emitter, "tool_result", Map.of("name", toolName, "result", result));
                    }
                }, memoryOwner(owner, scoped.agentId()));
                if (streamedContent.isEmpty()
                        && response != null
                        && response.response() != null
                        && !response.response().isBlank()) {
                    send(emitter, "token", Map.of("text", response.response()));
                }
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

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "global";
        }
        return jwt.getSubject();
    }

    private AgentRequest scopedRequest(AgentRequest request, String owner) {
        if (request == null) {
            return null;
        }
        String systemPrompt = request.systemPrompt();
        if (request.agentId() != null && !request.agentId().isBlank()) {
            systemPrompt = userAgentService.find(owner, request.agentId())
                    .map(UserAgent::systemPrompt)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        }
        String sessionId = request.sessionId();
        String scopedSessionId = scopeSession(owner, request.agentId(), sessionId);
        return new AgentRequest(
                request.prompt(),
                request.model(),
                request.decision(),
                request.complex(),
                request.simple(),
                request.agentId(),
                systemPrompt,
                scopedSessionId,
                request.recentMessages());
    }

    private static String scopeSession(String owner, String agentId, String sessionId) {
        String base = memoryOwner(owner, agentId);
        if (sessionId == null || sessionId.isBlank()) {
            return base;
        }
        return base + ":" + sessionId;
    }

    private static String memoryOwner(String owner, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return owner;
        }
        if (owner == null || owner.isBlank()) {
            return agentId;
        }
        return owner + ":" + agentId;
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
        if (ex instanceof ResponseStatusException statusException) {
            String message = statusException.getReason() != null
                    ? statusException.getReason()
                    : statusException.getMessage();
            return ResponseEntity.status(statusException.getStatusCode())
                    .body(Map.of("error", message != null ? message : statusException.getClass().getSimpleName()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
