package com.example.agent;

import com.example.agent.model.ChatMessage;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process {@link ConversationStore}: keeps each session's recent turns in a
 * {@link ConcurrentHashMap}. Expired sessions are never returned (each access checks the entry's
 * own age), and a full sweep of idle sessions runs at most once per minute so abandoned
 * conversations do not accumulate forever without paying a whole-map scan on every request.
 *
 * <p>This is the default and works without any external dependency. It is per-instance only, so
 * sessions are not shared across multiple server instances.
 */
public class InMemoryConversationStore implements ConversationStore {

    private record Entry(List<ChatMessage> messages, Instant lastAccess) {}

    private static final long SWEEP_INTERVAL_MILLIS = 60_000;

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepMillis = new AtomicLong(Long.MIN_VALUE);
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
        sweepIfDue();
        Instant cutoff = cutoff();
        // Returning null from computeIfPresent removes the mapping, so an expired session is
        // evicted (and treated as absent) even between full sweeps.
        Entry entry = sessions.computeIfPresent(sessionId, (id, e) ->
                isExpired(e, cutoff) ? null : new Entry(e.messages(), clock.instant()));
        return entry == null ? List.of() : entry.messages();
    }

    @Override
    public void append(String sessionId, List<ChatMessage> newMessages, int maxMessages) {
        sweepIfDue();
        Instant cutoff = cutoff();
        // compute holds the per-bin lock for the duration of the remapping, so the append-and-trim
        // is atomic: two threads recording into the same session cannot lose each other's turns.
        sessions.compute(sessionId, (id, existing) -> {
            boolean expired = existing == null || isExpired(existing, cutoff);
            List<ChatMessage> merged = new ArrayList<>(expired ? List.of() : existing.messages());
            merged.addAll(newMessages);
            if (merged.size() > maxMessages) {
                merged = merged.subList(merged.size() - maxMessages, merged.size());
            }
            return new Entry(List.copyOf(merged), clock.instant());
        });
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessions.remove(sessionId);
    }

    /** Sweeps idle sessions at most once per {@link #SWEEP_INTERVAL_MILLIS}. */
    private void sweepIfDue() {
        long now = clock.millis();
        long last = lastSweepMillis.get();
        if (now - last < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        if (lastSweepMillis.compareAndSet(last, now)) {
            Instant cutoff = cutoff();
            if (cutoff != null) {
                sessions.values().removeIf(e -> isExpired(e, cutoff));
            }
        }
    }

    private Instant cutoff() {
        return ttl == null ? null : clock.instant().minus(ttl);
    }

    private static boolean isExpired(Entry entry, Instant cutoff) {
        return cutoff != null && entry.lastAccess().isBefore(cutoff);
    }
}
