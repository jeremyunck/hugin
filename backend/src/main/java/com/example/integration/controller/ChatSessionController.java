package com.example.integration.controller;

import com.example.integration.service.ChatSessionEvent;
import com.example.integration.service.ChatSessionEventBroker;
import com.example.integration.service.ChatSessionMessageAcceptance;
import com.example.integration.service.ChatSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);
    private static final long HEARTBEAT_SECONDS = 20;

    private final ChatSessionService chatSessionService;
    private final ChatSessionEventBroker broker;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService chatStreamHeartbeatExecutor;

    public ChatSessionController(ChatSessionService chatSessionService,
                                 ChatSessionEventBroker broker,
                                 ObjectMapper objectMapper,
                                 ScheduledExecutorService chatStreamHeartbeatExecutor) {
        this.chatSessionService = chatSessionService;
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.chatStreamHeartbeatExecutor = chatStreamHeartbeatExecutor;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<ChatSessionMessageResponse> createMessage(@PathVariable String sessionId,
                                                                    @RequestBody ChatSessionMessageRequest request,
                                                                    @AuthenticationPrincipal Jwt jwt) {
        ChatSessionMessageAcceptance accepted = chatSessionService.createMessage(sessionId, owner(jwt), request);
        return ResponseEntity.ok(new ChatSessionMessageResponse(
                accepted.sessionId(),
                accepted.messageId(),
                accepted.runId(),
                accepted.lastSeq()));
    }

    @GetMapping("/{sessionId}/events")
    public ResponseEntity<ChatSessionEventsResponse> readEvents(@PathVariable String sessionId,
                                                                @RequestParam(defaultValue = "0") long afterSeq,
                                                                @AuthenticationPrincipal Jwt jwt) {
        List<ChatSessionEventResponse> events = chatSessionService.readEvents(sessionId, owner(jwt), afterSeq).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(new ChatSessionEventsResponse(sessionId, events));
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestParam(defaultValue = "0") long afterSeq,
                             @AuthenticationPrincipal Jwt jwt) {
        // A not-yet-persisted session is allowed: the UI opens the event stream for a freshly
        // created chat before its first message creates the session row, so requiring existence
        // here would 404 every new conversation. Existing sessions must still belong to the caller.
        if (!chatSessionService.allowStream(sessionId, owner(jwt))) {
            throw new ResponseStatusException(NOT_FOUND, "Chat session not found");
        }
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(15).toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);

        chatSessionService.readEvents(sessionId, owner(jwt), afterSeq).forEach(event -> sendEvent(emitter, closed, event));
        Runnable unsubscribe = broker.subscribe(sessionId, event -> sendEvent(emitter, closed, event));
        ScheduledFuture<?> heartbeat = chatStreamHeartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, closed),
                HEARTBEAT_SECONDS,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                unsubscribe.run();
                heartbeat.cancel(true);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    private ChatSessionEventResponse toResponse(ChatSessionEvent event) {
        return new ChatSessionEventResponse(
                event.id(),
                event.seq(),
                event.type(),
                event.messageId(),
                event.runId(),
                event.role(),
                event.content(),
                event.metadata(),
                event.createdAt());
    }

    private void sendEvent(SseEmitter emitter, AtomicBoolean closed, ChatSessionEvent event) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("chat_event")
                    .data(objectMapper.writeValueAsString(toResponse(event))));
        } catch (IOException e) {
            log.debug("Could not send chat session SSE event {}: {}", event.seq(), e.getMessage());
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }

    private void sendHeartbeat(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException e) {
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "global";
        }
        return jwt.getSubject();
    }
}
