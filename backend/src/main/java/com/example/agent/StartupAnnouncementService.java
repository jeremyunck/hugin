package com.example.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists a one-shot announcement message across JVM restarts.
 *
 * <p>Before a restart (e.g. after {@code self_update}) a caller writes a message via
 * {@link #set}. On the next startup {@link #load} reads and deletes the file so it fires
 * exactly once. {@link AgentService} calls {@link #consume} on every request until the
 * message has been surfaced to the user.
 */
@Service
public class StartupAnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(StartupAnnouncementService.class);

    private final Path file;
    private final AtomicReference<String> pending = new AtomicReference<>();

    public StartupAnnouncementService(
            @Value("${agent.startup-announcement-file:${user.home}/.bouw/startup-announcement}") String path) {
        this.file = Path.of(path);
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) return;
        try {
            String msg = Files.readString(file).trim();
            if (!msg.isBlank()) {
                pending.set(msg);
                log.info("Startup announcement loaded: {}", msg);
            }
        } catch (IOException e) {
            log.warn("Could not read startup announcement from {}: {}", file, e.getMessage());
            return;
        }
        // Delete after setting pending so a deletion failure doesn't lose the message.
        // Log a warning because if the file survives it will be re-delivered on the next restart.
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete startup announcement file {}, it may be re-delivered on next restart: {}",
                    file, e.getMessage());
        }
    }

    /** Persists {@code message} to disk so the next startup can pick it up. */
    public void set(String message) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, message);
        } catch (IOException e) {
            log.warn("Could not write startup announcement to {}: {}", file, e.getMessage());
        }
    }

    /**
     * Returns the pending announcement and clears it so it is delivered exactly once.
     * Returns empty if no announcement is pending.
     */
    public Optional<String> consume() {
        String msg = pending.getAndSet(null);
        return (msg != null && !msg.isBlank()) ? Optional.of(msg) : Optional.empty();
    }
}
