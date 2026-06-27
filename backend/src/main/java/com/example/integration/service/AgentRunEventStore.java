package com.example.integration.service;

import com.example.agent.model.AgentRunEvent;
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
 * Persists regular agent-stream events so clients can resume a detached run without rebuilding the
 * UI from conversation history.
 */
@Service
public class AgentRunEventStore {

    private final Path agentHome;
    private final ObjectMapper objectMapper;

    public AgentRunEventStore(@Value("${agent.home:${user.home}/.bouw}") String agentHome, ObjectMapper objectMapper) {
        this.agentHome = Path.of(agentHome);
        this.objectMapper = objectMapper;
    }

    public synchronized AgentRunEvent append(String runId, String owner, String sessionId, String type, Map<String, ?> data) {
        try {
            Path file = eventFile(runId);
            Files.createDirectories(file.getParent());
            long nextEventId = 1;
            if (Files.exists(file)) {
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    nextEventId = lines.count() + 1;
                }
            }
            AgentRunEvent event = new AgentRunEvent(
                    runId,
                    owner,
                    sessionId,
                    nextEventId,
                    type,
                    Instant.now(),
                    objectMapper.writeValueAsString(data));
            Files.writeString(
                    file,
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
            return event;
        } catch (Exception ignored) {
            return null;
        }
    }

    public List<AgentRunEvent> read(String runId, String owner, long afterEventId) {
        Path file = eventFile(runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<AgentRunEvent> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                AgentRunEvent event = objectMapper.readValue(line, new TypeReference<AgentRunEvent>() {});
                if (!owner.equals(event.owner()) || event.eventId() <= afterEventId) {
                    continue;
                }
                result.add(event);
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path eventFile(String runId) {
        return agentHome.resolve("agent-runs").resolve(runId).resolve("events.jsonl");
    }
}
