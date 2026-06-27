package com.example.integration.service;

import com.example.agent.model.CloudAgentEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persists cloud-agent SSE events to a per-agent JSONL file so clients can replay runs.
 *
 * <p>Events live under {@code $AGENT_HOME/runs/<id>/events.jsonl} instead of the transient
 * agent workspace directory so cleanup can remove the repo checkout without erasing history.
 */
@Service
public class CloudAgentEventStore {

    private final Path agentHome;
    private final ObjectMapper objectMapper;

    public CloudAgentEventStore(@Value("${agent.home:${user.home}/.bouw}") String agentHome, ObjectMapper objectMapper) {
        this.agentHome = Path.of(agentHome);
        this.objectMapper = objectMapper;
    }

    public void append(String agentId, String type, Map<String, ?> data) {
        try {
            Files.createDirectories(eventFile(agentId).getParent());
            CloudAgentEvent event = new CloudAgentEvent(
                    agentId,
                    type,
                    Instant.now(),
                    objectMapper.writeValueAsString(data));
            Files.writeString(eventFile(agentId),
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(eventFile(agentId)) ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception ignored) {
            // best-effort persistence
        }
    }

    public List<CloudAgentEvent> read(String agentId) {
        Path file = eventFile(agentId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<CloudAgentEvent> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                result.add(objectMapper.readValue(line, new TypeReference<CloudAgentEvent>() {}));
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path eventFile(String agentId) {
        return agentHome.resolve("runs").resolve(agentId).resolve("events.jsonl");
    }
}
