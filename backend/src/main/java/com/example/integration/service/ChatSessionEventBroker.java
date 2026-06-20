package com.example.integration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class ChatSessionEventBroker {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionEventBroker.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<ChatSessionEvent>>> listeners =
            new ConcurrentHashMap<>();

    public Runnable subscribe(String sessionId, Consumer<ChatSessionEvent> listener) {
        listeners.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> listeners.computeIfPresent(sessionId, (key, callbacks) -> {
            callbacks.remove(listener);
            return callbacks.isEmpty() ? null : callbacks;
        });
    }

    public void publish(ChatSessionEvent event) {
        var callbacks = listeners.get(event.sessionId());
        if (callbacks == null) {
            return;
        }
        for (Consumer<ChatSessionEvent> callback : callbacks) {
            try {
                callback.accept(event);
            } catch (RuntimeException e) {
                // A subscriber (e.g. a dead SSE emitter) must not break delivery to the others.
                log.debug("Chat session subscriber failed for event {}: {}", event.seq(), e.getMessage());
            }
        }
    }
}
