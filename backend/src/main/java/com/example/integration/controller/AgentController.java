package com.example.integration.controller;

import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.AgentRunRegistry;
import com.example.agent.DeveloperModeService;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.ChatMessage;
import com.example.integration.agent.UserAgent;
import com.example.integration.agent.UserAgentService;
import com.example.integration.controller.BugReportSummaryResponse;
import com.example.integration.service.BugReportCatalogService;
import com.example.integration.service.BugReportService;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final BugReportCatalogService bugReportCatalogService;
    private final BugReportService bugReportService;
    private final AgentRunRegistry runRegistry;

    public AgentController(AgentService agentService,
                           ObjectMapper objectMapper,
                           ExecutorService agentStreamExecutor,
                           DeveloperModeService developerModeService,
                           UserAgentService userAgentService,
                           AgentRunRegistry runRegistry,
                           BugReportCatalogService bugReportCatalogService,
                           BugReportService bugReportService,
                           @Value("${agent.request-timeout:5m}") Duration requestTimeout) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.streamExecutor = agentStreamExecutor;
        this.developerModeService = developerModeService;
        this.userAgentService = userAgentService;
        this.runRegistry = runRegistry;
        this.bugReportCatalogService = bugReportCatalogService;
        this.bugReportService = bugReportService;
        // Allow a margin beyond the agent's own deadline before the SSE connection is torn down.
        this.streamTimeoutMillis = requestTimeout.plusSeconds(60).toMillis();
    }

    @GetMapping("/tools")
    public List<AgentService.ToolSummary> listTools(@RequestParam(required = false) String sandboxId) {
        // Mirror the dynamic tool set the agent would advertise for this request: a blank sandboxId
        // is a "pure chat" with no filesystem tools; a sandbox id includes the workspace tools.
        return agentService.availableTools(sandboxId);
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AgentRequest request, @AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        AgentRequest scoped = scopedRequest(request, owner);
        AgentResponse response = agentService.chat(scoped, memoryOwner(owner, scoped.agentId()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<List<ChatMessage>> history(@RequestParam String sessionId,
                                                     @RequestParam(required = false) String agentId,
                                                     @AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        return ResponseEntity.ok(agentService.history(owner, agentId, sessionId));
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> deleteHistory(@RequestParam String sessionId,
                                              @RequestParam(required = false) String agentId,
                                              @AuthenticationPrincipal Jwt jwt) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        agentService.deleteHistory(owner(jwt), agentId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/runs")
    public ResponseEntity<List<AgentRunRegistry.ActiveRun>> runs(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(runRegistry.list(owner(jwt)));
    }

    @DeleteMapping("/runs/{id}")
    public ResponseEntity<Void> cancelRun(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return runRegistry.cancel(owner(jwt), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/bug-report")
    public ResponseEntity<BugReportResponse> saveBugReport(@RequestBody BugReportRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        String owner = owner(jwt);
        List<ChatMessage> history = agentService.history(owner, request.agentId(), request.sessionId());
        var saved = bugReportService.writeReport(
                request.sessionId(),
                request.title(),
                owner,
                request.agentId(),
                request.sandboxId(),
                history,
                request.thread(),
                request.clientContext());
        return ResponseEntity.ok(new BugReportResponse(
                saved.id(),
                saved.relativePath(),
                saved.logFiles()));
    }

    @GetMapping("/bug-reports")
    public ResponseEntity<List<BugReportSummaryResponse>> listBugReports(@AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        return ResponseEntity.ok(bugReportCatalogService.list(owner).stream()
                .map(report -> new BugReportSummaryResponse(
                        report.id(),
                        report.title(),
                        report.relativePath(),
                        report.createdAt()))
                .toList());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AgentRequest request, @AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        AgentRequest scoped = scopedRequest(request, owner);
        SseEmitter emitter = createEmitter();

        streamExecutor.execute(() -> {
            String runId = runRegistry.register(owner, scoped, scoped.model(), Thread.currentThread());
            AtomicBoolean streamOpen = new AtomicBoolean(true);
            sendIfOpen(runId, emitter, streamOpen, "config", Map.of("developerMode", developerModeService.isEnabled()));
            try {
                StringBuilder streamedContent = new StringBuilder();
                AgentResponse response = agentService.chatStream(scoped, new AgentStreamListener() {
                    @Override
                    public void onConfig(boolean developerMode) {
                        sendIfOpen(runId, emitter, streamOpen, "config", Map.of("developerMode", developerMode));
                    }

                    @Override
                    public void onContent(String delta) {
                        streamedContent.append(delta);
                        sendIfOpen(runId, emitter, streamOpen, "token", Map.of("text", delta));
                    }

                    @Override
                    public void onReasoning(String delta) {
                        sendIfOpen(runId, emitter, streamOpen, "reasoning", Map.of("text", delta));
                    }

                    @Override
                    public void onToolCall(String toolName, String arguments) {
                        sendIfOpen(runId, emitter, streamOpen, "tool", Map.of("name", toolName, "args", arguments));
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        sendIfOpen(runId, emitter, streamOpen, "tool_result", Map.of("name", toolName, "result", result));
                    }
                }, memoryOwner(owner, scoped.agentId()));
                if (streamedContent.isEmpty()
                        && response != null
                        && response.response() != null
                        && !response.response().isBlank()) {
                    sendIfOpen(runId, emitter, streamOpen, "token", Map.of("text", response.response()));
                }
                sendIfOpen(runId, emitter, streamOpen, "done", Map.of());
                completeSafely(emitter);
            } catch (com.example.agent.AgentRunCancelledException e) {
                log.info("Agent stream cancelled: {}", runId);
                sendIfOpen(runId, emitter, streamOpen, "error", Map.of("message", e.getMessage()));
                completeSafely(emitter);
            } catch (Exception e) {
                log.warn("Agent stream failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendIfOpen(runId, emitter, streamOpen, "error", Map.of("message", message));
                completeSafely(emitter);
            } finally {
                runRegistry.unregister(runId);
            }
        });

        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(streamTimeoutMillis);
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
        // AgentService applies owner/agent scoping when it loads and records conversation memory.
        // Keep the caller's raw sessionId here so history reads and writes use the same key.
        return new AgentRequest(
                request.prompt(),
                request.attachments(),
                request.model(),
                request.reasoningEffort(),
                request.decision(),
                request.complex(),
                request.simple(),
                request.agentId(),
                systemPrompt,
                request.sessionId(),
                request.priorMessages(),
                request.recentMessages(),
                request.sandboxId(),
                request.clientManagedContext());
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

    private boolean send(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            // Write the pre-serialized JSON verbatim as the SSE data line (passing a MediaType
            // here would route the String through Jackson again and double-encode it).
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
            return true;
        } catch (IOException e) {
            // Client disconnected; nothing more we can do for this stream.
            log.debug("Could not send SSE event '{}': {}", event, e.getMessage());
            return false;
        }
    }

    private void sendIfOpen(String runId, SseEmitter emitter, AtomicBoolean streamOpen, String event, Map<String, ?> data) {
        if (!streamOpen.get()) {
            return;
        }
        if (!send(emitter, event, data) && streamOpen.compareAndSet(true, false)) {
            log.debug("Agent stream client disconnected during '{}' event; continuing run in background", event);
            runRegistry.markDisconnected(runId);
        }
    }

    private void completeSafely(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE emitter was already completed: {}", e.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        if (SseRequestSupport.acceptsEventStream(request, response)) {
            log.debug("Suppressing HTTP error body for SSE request: {}", ex.getMessage(), ex);
            if (ex instanceof ResponseStatusException statusException) {
                response.setStatus(statusException.getStatusCode().value());
            } else {
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            }
            return null;
        }
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
