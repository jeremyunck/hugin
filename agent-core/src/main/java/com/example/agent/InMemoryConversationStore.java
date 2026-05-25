package com.example.agent;

import com.example.agent.model.ChatMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link ConversationStore}: keeps each session's recent turns in a
 * {@link ConcurrentHashMap}. Idle sessions are evicted once they pass {@code ttl}, swept lazily on
 * access so abandoned conversations do not accumulate forever.
 *
 * <p>This is the default and works without any external dependency. It is per-instance only, so
 * sessions are not shared across multiple server instances.
 */
@Component
@ConditionalOnProperty(prefix = "conversation.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InMemoryConversationStore implements ConversationStore {

    private record Entry(List<ChatMessage> messages, Instant lastAccess) {}

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public InMemoryConversationStore(ConversationMemoryProperties properties) {
        this(properties, Clock.systemUTC());
    }

    InMemoryConversationStore(ConversationMemoryProperties properties, Clock clock) {
        this.ttl = properties.ttl();
        this.clock = clock;
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        purgeExpired();
        Entry entry = sessions.computeIfPresent(sessionId,
                (id, e) -> new Entry(e.messages(), clock.instant()));
        return entry == null ? List.of() : entry.messages();
    }

    @Override
    public void append(String sessionId, List<ChatMessage> newMessages, int maxMessages) {
        purgeExpired();
        // compute holds the per-bin lock for the duration of the remapping, so the append-and-trim
        // is atomic: two threads recording into the same session cannot lose each other's turns.
        sessions.compute(sessionId, (id, existing) -> {
            List<ChatMessage> merged =
                    new ArrayList<>(existing == null ? List.of() : existing.messages());
            merged.addAll(newMessages);
            if (merged.size() > maxMessages) {
                merged = merged.subList(merged.size() - maxMessages, merged.size());
            }
            return new Entry(List.copyOf(merged), clock.instant());
        });
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        sessions.values().removeIf(e -> e.lastAccess().isBefore(cutoff));
    }
}
