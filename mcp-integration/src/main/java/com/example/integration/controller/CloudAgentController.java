package com.example.integration.controller;

import com.example.agent.AgentStreamListener;
import com.example.agent.CloudAgentService;
import com.example.agent.model.AgentInfo;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * REST API for cloud agents.
 *
 * <pre>
 * POST   /api/v1/agents           create + run a cloud agent (streams SSE)
 * GET    /api/v1/agents           list all known agents + status
 * GET    /api/v1/agents/{id}      get one agent's metadata
 * DELETE /api/v1/agents/{id}      stop + delete the agent directory
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/agents")
public class CloudAgentController {

    private static final Logger log = LoggerFactory.getLogger(CloudAgentController.class);

    /** Request body for {@code POST /api/v1/agents}. */
    public record CreateAgentRequest(
            String repoUrl,
            String task,
            String branch,
            String model) {}

    private final CloudAgentService cloudAgentService;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final long streamTimeoutMillis;

    public CloudAgentController(
            CloudAgentService cloudAgentService,
            ObjectMapper objectMapper,
            ExecutorService agentStreamExecutor,
            @Value("${agent.request-timeout:5m}") Duration requestTimeout) {
        this.cloudAgentService = cloudAgentService;
        this.objectMapper = objectMapper;
        this.streamExecutor = agentStreamExecutor;
        this.streamTimeoutMillis = requestTimeout.plusSeconds(60).toMillis();
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@RequestBody CreateAgentRequest req) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);

        streamExecutor.execute(() -> {
            AgentInfo info;
            try {
                info = cloudAgentService.create(req.repoUrl(), req.task(), req.branch(), req.model());
                send(emitter, "agent_created", Map.of("id", info.id(), "branch", info.branch()));
            } catch (Exception e) {
                log.warn("Cloud agent creation failed", e);
                send(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                emitter.complete();
                return;
            }

            final String agentId = info.id();
            final String baseBranch = req.branch() != null ? req.branch() : "main";
            try {
                cloudAgentService.run(agentId, req.model(), baseBranch, new AgentStreamListener() {
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

                    @Override
                    public void onPrOpened(String prUrl) {
                        send(emitter, "pr_opened", Map.of("url", prUrl));
                    }
                });
                send(emitter, "done", Map.of("id", agentId));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Cloud agent {} failed", agentId, e);
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                send(emitter, "error", Map.of("id", agentId, "message", message));
                emitter.complete();
            }
        });

        return emitter;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (cloudAgentService.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        cloudAgentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private void send(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            log.debug("Could not send SSE event '{}': {}", event, e.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
