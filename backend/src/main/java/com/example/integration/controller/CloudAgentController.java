package com.example.integration.controller;

import com.example.agent.AgentStreamListener;
import com.example.agent.CloudAgentService;
import com.example.agent.model.AgentInfo;
import com.example.agent.model.CloudAgentEvent;
import com.example.integration.service.CloudAgentEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * REST API for cloud agents.
 *
 * <pre>
 * POST   /api/agents           create + run a cloud agent (streams SSE like /api/agent/stream)
 * GET    /api/agents           list all known agents + status
 * GET    /api/agents/{id}      get one agent's metadata
 * DELETE /api/agents/{id}      stop + delete the agent directory
 * </pre>
 */
@RestController
@RequestMapping("/api/agents")
public class CloudAgentController {

    private static final Logger log = LoggerFactory.getLogger(CloudAgentController.class);

    /** Request body for {@code POST /api/agents}. */
    public record CreateAgentRequest(
            String repoUrl,
            String task,
            String branch,
            String model) {}

    private final CloudAgentService cloudAgentService;
    private final CloudAgentEventStore eventStore;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final long streamTimeoutMillis;

    public CloudAgentController(
            CloudAgentService cloudAgentService,
            CloudAgentEventStore eventStore,
            ObjectMapper objectMapper,
            ExecutorService agentStreamExecutor,
            @Value("${agent.request-timeout:5m}") Duration requestTimeout) {
        this.cloudAgentService = cloudAgentService;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.streamExecutor = agentStreamExecutor;
        this.streamTimeoutMillis = requestTimeout.plusSeconds(60).toMillis();
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@RequestBody CreateAgentRequest req) {
        SseEmitter emitter = createEmitter();

        streamExecutor.execute(() -> {
            AgentInfo info;
            try {
                info = cloudAgentService.create(req.repoUrl(), req.task(), req.branch(), req.model());
                if (!send(info.id(), emitter, "agent_created", Map.of("id", info.id(), "branch", info.branch()))) {
                    emitter.complete();
                    return;
                }
            } catch (Exception e) {
                log.warn("Cloud agent creation failed", e);
                send(null, emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                emitter.complete();
                return;
            }

            final String agentId = info.id();
            try {
                CloudAgentService.RunResult result = cloudAgentService.run(agentId, req.model(), new AgentStreamListener() {
                    @Override
                    public void onContent(String delta) {
                        SseRequestSupport.ensureConnected(send(agentId, emitter, "token", Map.of("text", delta)));
                    }

                    @Override
                    public void onReasoning(String delta) {
                        SseRequestSupport.ensureConnected(send(agentId, emitter, "reasoning", Map.of("text", delta)));
                    }

                    @Override
                    public void onToolCall(String toolName, String arguments) {
                        SseRequestSupport.ensureConnected(send(agentId, emitter, "tool", Map.of("name", toolName, "args", arguments)));
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        SseRequestSupport.ensureConnected(send(agentId, emitter, "tool_result", Map.of("name", toolName, "result", result)));
                    }
                });
                result.pullRequestUrl().ifPresent(url ->
                        SseRequestSupport.ensureConnected(send(agentId, emitter, "pr_opened", Map.of("id", agentId, "url", url))));
                SseRequestSupport.ensureConnected(send(agentId, emitter, "done", Map.of("id", agentId, "prUrl", result.pullRequestUrl().orElse(""),
                        "changed", result.changed())));
                emitter.complete();
            } catch (SseRequestSupport.ClientDisconnectedException e) {
                log.debug("Cloud agent stream client disconnected: {}", agentId);
                emitter.complete();
            } catch (Exception e) {
                log.warn("Cloud agent {} failed", agentId, e);
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                send(agentId, emitter, "error", Map.of("id", agentId, "message", message));
                emitter.complete();
            }
        });

        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(streamTimeoutMillis);
    }

    @GetMapping
    public List<AgentInfo> list() {
        return cloudAgentService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentInfo> get(@PathVariable String id) {
        return cloudAgentService.get(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<CloudAgentEvent>> events(@PathVariable String id) {
        List<CloudAgentEvent> events = eventStore.read(id);
        if (events.isEmpty() && cloudAgentService.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(events);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (cloudAgentService.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        cloudAgentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private boolean send(String agentId, SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
            if (agentId != null) {
                eventStore.append(agentId, event, data);
            }
            return true;
        } catch (IOException e) {
            log.debug("Could not send SSE event '{}': {}", event, e.getMessage());
            return false;
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        if (SseRequestSupport.acceptsEventStream(request, response)) {
            log.debug("Suppressing HTTP error body for SSE request: {}", ex.getMessage(), ex);
            response.setStatus(ex instanceof org.springframework.web.server.ResponseStatusException statusException
                    ? statusException.getStatusCode().value()
                    : HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
