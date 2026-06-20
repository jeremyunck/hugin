package com.example.integration.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionEventBrokerTest {

    private final ChatSessionEventBroker broker = new ChatSessionEventBroker();

    @Test
    void publishDeliversEventsToSubscribersOfTheSameSession() {
        List<ChatSessionEvent> received = new CopyOnWriteArrayList<>();
        broker.subscribe("session-1", received::add);

        broker.publish(event("session-1", 1));
        broker.publish(event("session-1", 2));

        assertThat(received).extracting(ChatSessionEvent::seq).containsExactly(1L, 2L);
    }

    @Test
    void publishIsScopedToTheTargetSession() {
        List<ChatSessionEvent> sessionOne = new CopyOnWriteArrayList<>();
        List<ChatSessionEvent> sessionTwo = new CopyOnWriteArrayList<>();
        broker.subscribe("session-1", sessionOne::add);
        broker.subscribe("session-2", sessionTwo::add);

        broker.publish(event("session-1", 1));

        assertThat(sessionOne).hasSize(1);
        assertThat(sessionTwo).isEmpty();
    }

    @Test
    void unsubscribeStopsFurtherDelivery() {
        List<ChatSessionEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = broker.subscribe("session-1", received::add);

        broker.publish(event("session-1", 1));
        unsubscribe.run();
        broker.publish(event("session-1", 2));

        assertThat(received).extracting(ChatSessionEvent::seq).containsExactly(1L);
    }

    @Test
    void publishToSessionWithoutSubscribersIsANoop() {
        // Should not throw even though nobody is listening.
        broker.publish(event("ghost-session", 1));
    }

    @Test
    void aFailingSubscriberDoesNotBreakDeliveryToOthers() {
        List<ChatSessionEvent> healthy = new CopyOnWriteArrayList<>();
        broker.subscribe("session-1", e -> {
            throw new IllegalStateException("dead emitter");
        });
        broker.subscribe("session-1", healthy::add);

        broker.publish(event("session-1", 1));

        assertThat(healthy).extracting(ChatSessionEvent::seq).containsExactly(1L);
    }

    private static ChatSessionEvent event(String sessionId, long seq) {
        return new ChatSessionEvent("event-" + seq, sessionId, "run-1", "message-1", seq,
                "assistant_token", "assistant", "token", Map.of(), Instant.now());
    }
}
