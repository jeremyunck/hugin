package com.example.agent;

import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link ConversationStore}: keeps conversation history in memory and mirrors it to a JSON
 * file so short-term chat context survives idle periods and service restarts.
 */
@Component
@ConditionalOnProperty(prefix = "conversation.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JsonFileConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileConversationStore.class);
    private static final long FLUSH_DELAY_MS = 1_000;

    private record Entry(List<ChatMessage> messages, Instant lastAccess) {}
    private record PersistedEntry(String sessionId, List<ChatMessage> messages, Instant lastAccess) {}

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conversation-memory-flush");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean flushPending = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();

    @Autowired
    public JsonFileConversationStore(ObjectMapper objectMapper, ConversationMemoryProperties properties) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    JsonFileConversationStore(ObjectMapper objectMapper, ConversationMemoryProperties properties, Clock clock) {
        this.objectMapper = objectMapper;
        this.file = resolve(properties.storeFile());
        this.ttl = properties.ttl();
        this.clock = clock;
        load();
    }

    private static Path resolve(String storeFile) {
        String expanded = storeFile.startsWith("~/")
                ? System.getProperty("user.home") + storeFile.substring(1)
                : storeFile;
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<ChatMessage> load(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return List.of();
        }
        if (isExpired(entry)) {
            sessions.remove(sessionId);
            scheduleFlush();
            return List.of();
        }
        sessions.put(sessionId, new Entry(entry.messages(), clock.instant()));
        return entry.messages();
    }

    @Override
    public synchronized void append(String sessionId, List<ChatMessage> newMessages, int maxMessages) {
        Entry existing = sessions.get(sessionId);
        List<ChatMessage> merged = new ArrayList<>();
        if (existing != null && !isExpired(existing)) {
            merged.addAll(existing.messages());
        }
        merged.addAll(newMessages);
        if (merged.size() > maxMessages) {
            merged = new ArrayList<>(merged.subList(merged.size() - maxMessages, merged.size()));
        }
        sessions.put(sessionId, new Entry(List.copyOf(merged), clock.instant()));
        scheduleFlush();
    }

    @Override
    public synchronized void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (sessions.remove(sessionId) != null) {
            scheduleFlush();
        }
    }

    private synchronized void load() {
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            List<PersistedEntry> loaded = objectMapper.readValue(
                    Files.readAllBytes(file), new TypeReference<List<PersistedEntry>>() {});
            for (PersistedEntry persisted : loaded) {
                Entry entry = new Entry(
                        persisted.messages() == null ? List.of() : List.copyOf(persisted.messages()),
                        persisted.lastAccess() == null ? clock.instant() : persisted.lastAccess());
                if (!isExpired(entry)) {
                    sessions.put(persisted.sessionId(), entry);
                }
            }
            log.info("Loaded {} conversation session(s) from {}", sessions.size(), file);
        } catch (IOException e) {
            log.warn("Could not read conversation memory from {}: {}", file, e.getMessage());
        }
    }

    private void scheduleFlush() {
        dirty.set(true);
        if (flushPending.compareAndSet(false, true)) {
            try {
                flusher.schedule(this::runFlush, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                flushPending.set(false);
            }
        }
    }

    private void runFlush() {
        flushPending.set(false);
        flushNow();
    }

    private synchronized void flushNow() {
        dirty.set(false);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            pruneExpiredSessions();
            List<PersistedEntry> persisted = new ArrayList<>(sessions.size());
            for (Map.Entry<String, Entry> session : sessions.entrySet()) {
                persisted.add(new PersistedEntry(
                        session.getKey(),
                        session.getValue().messages(),
                        session.getValue().lastAccess()));
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), persisted);
        } catch (IOException e) {
            log.warn("Could not persist conversation memory to {}: {}", file, e.getMessage());
        }
    }

    private boolean isExpired(Entry entry) {
        return ttl != null && entry.lastAccess().isBefore(clock.instant().minus(ttl));
    }

    private void pruneExpiredSessions() {
        if (ttl == null) {
            return;
        }
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    @PreDestroy
    public void close() {
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(2, TimeUnit.SECONDS)) {
                flusher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            flusher.shutdownNow();
        } finally {
            flushPending.set(false);
            if (dirty.get()) {
                flushNow();
            }
        }
    }
}
