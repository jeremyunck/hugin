package com.example.integration.service;

import com.example.agent.scheduler.ScheduledResultDelivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ScheduledResultDelivery} implementation for the runnable server. The server cannot push to
 * a chat client directly (clients connect to it, not the other way round), so finished scheduled
 * results are fanned out over Server-Sent Events to any subscribed delivery clients — notably the
 * Discord bot, which posts each result to the originating channel/DM identified by {@code target}.
 *
 * <p>This is the one place that bridges transport-agnostic {@code agent-core} scheduling to an
 * actual delivery channel, mirroring how the agent bridges external tool providers.
 */
@Component
public class OutboxResultDelivery implements ScheduledResultDelivery {

    private static final Logger log = LoggerFactory.getLogger(OutboxResultDelivery.class);
    private static final int RECENT_BUFFER = 50;

    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Map<String, Object>> recent = new ConcurrentLinkedDeque<>();

    public OutboxResultDelivery(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Registers a long-lived SSE subscriber (a delivery client) and wires its cleanup. */
    public SseEmitter subscribe(long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> { subscribers.remove(emitter); emitter.complete(); });
        emitter.onError(e -> subscribers.remove(emitter));
        subscribers.add(emitter);
        log.debug("Delivery subscriber registered ({} active)", subscribers.size());
        return emitter;
    }

    /** A snapshot of recently delivered results, newest last (for inspection/debugging). */
    public List<Map<String, Object>> recent() {
        return List.copyOf(recent);
    }

    @Override
    public void deliver(String target, String prompt, String result) {
        Map<String, Object> payload = Map.of(
                "target", target == null ? "" : target,
                "prompt", prompt == null ? "" : prompt,
                "result", result == null ? "" : result,
                "timestamp", Instant.now().toString());

        recent.addLast(payload);
        while (recent.size() > RECENT_BUFFER) {
            recent.pollFirst();
        }

        log.info("Delivering scheduled result to target='{}' ({} subscriber(s))",
                target, subscribers.size());

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            log.error("Could not serialise scheduled delivery", e);
            return;
        }

        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("delivery").data(json));
            } catch (Exception e) {
                // Subscriber went away mid-send; drop it.
                subscribers.remove(emitter);
                log.debug("Dropped dead delivery subscriber: {}", e.getMessage());
            }
        }
    }
}
