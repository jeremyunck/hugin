package com.example.integration.service;

import com.example.integration.sandbox.ProjectSandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically destroys isolated project-chat sandboxes that have been idle past
 * {@code bouw.sandbox.idle-timeout-hours}, reclaiming their containers, volumes, and database rows.
 *
 * <p>Runs once an hour. Each sandbox's expiry is refreshed whenever the chat uses it (see
 * {@link SandboxSessionService#touch}), so an actively used chat never expires.
 */
@Service
public class SandboxCleanupService {

    private static final Logger log = LoggerFactory.getLogger(SandboxCleanupService.class);

    private final SandboxSessionService sessionService;
    private final ProjectSandboxProperties properties;

    public SandboxCleanupService(SandboxSessionService sessionService, ProjectSandboxProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
    }

    /** Hourly sweep of expired sandboxes. */
    @Scheduled(fixedDelayString = "${bouw.sandbox.cleanup-interval-ms:3600000}", initialDelayString = "${bouw.sandbox.cleanup-initial-delay-ms:600000}")
    public void cleanupExpiredSandboxes() {
        if (!properties.enabled()) {
            return;
        }
        try {
            int destroyed = sessionService.destroyExpired();
            if (destroyed > 0) {
                log.info("Idle-sandbox cleanup destroyed {} expired sandbox(es)", destroyed);
            }
        } catch (Exception e) {
            log.warn("Idle-sandbox cleanup failed: {}", e.getMessage());
        }
    }
}
