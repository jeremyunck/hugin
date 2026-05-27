package com.example.integration.service;

import com.example.agent.RunStore;
import com.example.agent.model.AgentInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File-based {@link RunStore} implementation.
 *
 * <p>Persists each agent run as {@code $AGENT_HOME/agents/<id>/agent.json}.
 * On startup, scans the directory and reloads all known runs so state survives restarts.
 */
@Component
@ConditionalOnProperty("agent.cloud.enabled")
public class FileRunStore implements RunStore {

    private static final Logger log = LoggerFactory.getLogger(FileRunStore.class);

    private final Path agentHome;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AgentInfo> agents = new ConcurrentHashMap<>();

    public FileRunStore(
            @Value("${agent.home:.}") String agentHome,
            ObjectMapper objectMapper) {
        this.agentHome = Path.of(agentHome);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void reload() {
        Path agentsDir = agentHome.resolve("agents");
        if (!Files.isDirectory(agentsDir)) {
            log.info("No agents directory at {} — nothing to reload", agentsDir);
            return;
        }

        int count = 0;
        try (Stream<Path> dirs = Files.list(agentsDir)) {
            for (Path agentDir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(agentDir)) continue;
                Path jsonFile = agentDir.resolve("agent.json");
                if (!Files.exists(jsonFile)) continue;
                try {
                    AgentInfo info = objectMapper.readValue(jsonFile.toFile(), AgentInfo.class);
                    agents.put(info.id(), info);
                    count++;
                } catch (IOException e) {
                    log.warn("Could not reload agent from {}: {}", jsonFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan agents directory {}: {}", agentsDir, e.getMessage());
        }
        log.info("Reloaded {} agent runs from {}", count, agentsDir);
    }

    @Override
    public void save(AgentInfo info) {
        agents.put(info.id(), info);
        persist(info);
    }

    @Override
    public List<AgentInfo> findAll() {
        return List.copyOf(agents.values());
    }

    @Override
    public Optional<AgentInfo> findById(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public void deleteById(String agentId) {
        agents.remove(agentId);
        Path agentDir = agentHome.resolve("agents").resolve(agentId);
        try {
            deleteRecursively(agentDir);
        } catch (IOException e) {
            log.warn("Could not delete agent directory {}: {}", agentDir, e.getMessage());
        }
    }

    private void persist(AgentInfo info) {
        Path agentDir = agentHome.resolve("agents").resolve(info.id());
        try {
            Files.createDirectories(agentDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(agentDir.resolve("agent.json").toFile(), info);
        } catch (IOException e) {
            log.warn("Could not persist agent {}: {}", info.id(), e.getMessage());
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); }
                      catch (IOException e) { /* best-effort */ }
                  });
        }
    }
}