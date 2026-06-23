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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

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

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String sessionId,
                                       @AuthenticationPrincipal Jwt jwt) {
        boolean cancelled = chatSessionService.cancelRun(sessionId, owner(jwt));
        // 202 when a run was stopped, 204 when there was nothing in flight. Either way the client can
        // safely re-enable the composer once the terminal event lands.
        return cancelled ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
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
        String owner = owner(jwt);
        // A not-yet-persisted session is allowed: the UI opens the event stream for a freshly
        // created chat before its first message creates the session row, so requiring existence
        // here would 404 every new conversation. Existing sessions must still belong to the caller.
        if (!chatSessionService.allowStream(sessionId, owner)) {
            throw new ResponseStatusException(NOT_FOUND, "Chat session not found");
        }
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(15).toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean cleanedUp = new AtomicBoolean(false);
        AtomicReference<Runnable> unsubscribe = new AtomicReference<>(() -> { });
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        // Release the broker subscription and heartbeat exactly once, whether the stream ends via the
        // client disconnecting (onCompletion/onError/onTimeout) or a failed send completing the emitter.
        // Gated on its own flag rather than `closed` so a send that flips `closed` before completing
        // the emitter cannot suppress this cleanup (which previously leaked the subscription/heartbeat).
        Runnable cleanup = () -> {
            if (cleanedUp.compareAndSet(false, true)) {
                closed.set(true);
                unsubscribe.get().run();
                ScheduledFuture<?> scheduled = heartbeat.get();
                if (scheduled != null) {
                    scheduled.cancel(true);
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());

        // Replay persisted history first so the client receives events strictly in seq order (it
        // drops anything not newer than its cursor), then attach the live subscription.
        chatSessionService.readEvents(sessionId, owner, afterSeq).forEach(event -> sendEvent(emitter, closed, event));
        if (closed.get()) {
            // A replay send already failed and completed the emitter; don't open a subscription or
            // heartbeat that would immediately leak.
            cleanup.run();
            return emitter;
        }
        unsubscribe.set(broker.subscribe(sessionId, event -> sendEvent(emitter, closed, event)));
        heartbeat.set(chatStreamHeartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, closed),
                HEARTBEAT_SECONDS,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS));
        // If a send raced to completion while the subscription/heartbeat were being wired up, release
        // them now instead of waiting for a callback that has already fired.
        if (closed.get()) {
            cleanup.run();
        }
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
        } catch (Exception e) {
            // The send can race a client disconnect: the emitter may complete between the closed
            // check above and here, in which case send() throws IllegalStateException rather than
            // IOException. Catch broadly so a dead emitter is retired quietly instead of propagating
            // into the broker's publish loop and disrupting other subscribers.
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
        } catch (Exception e) {
            // See sendEvent: a heartbeat can race a disconnect and throw IllegalStateException on an
            // already-completed emitter, so handle any failure by retiring this emitter.
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            // Chat sessions are always owner-scoped. /api/chat/** is already authenticated at the
            // filter level, so this is defence in depth: fail closed rather than fall back to a
            // shared "global" owner that an unauthenticated caller could otherwise read or write.
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication required");
        }
        return jwt.getSubject();
    }
}
