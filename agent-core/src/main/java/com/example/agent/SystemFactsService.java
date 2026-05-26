package com.example.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Probes the host machine once at startup and exposes the result as a compact system-message
 * summary that {@link AgentService} injects into every agent request.
 *
 * <p>Unlike long-term semantic memory, this summary is injected deterministically — no
 * similarity threshold, no Redis required — so the model always knows the machine's capabilities.
 */
@Service
public class SystemFactsService {

    private static final Logger log = LoggerFactory.getLogger(SystemFactsService.class);

    private final Path agentHome;
    private SystemFacts facts;

    public SystemFactsService(@Value("${agent.home:.}") String agentHome) {
        this.agentHome = Path.of(agentHome);
    }

    @PostConstruct
    void probe() {
        try {
            facts = EnvironmentProbe.probe(agentHome);
            log.info("System facts: {} cores, Java {}, git={}",
                    facts.availableProcessors(), facts.javaVersion(),
                    facts.toolchains().getOrDefault("git", false));
        } catch (Exception e) {
            log.warn("Environment probe failed: {}", e.getMessage());
        }
    }

    public SystemFacts facts() {
        return facts;
    }

    /** Returns the compact summary to inject as a system message, or empty string on failure. */
    public String summary() {
        return facts != null ? facts.summary() : "";
    }
}
