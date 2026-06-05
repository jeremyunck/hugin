package com.example.integration.agent;

import com.example.agent.prompts.Prompts;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserAgentService {

    private final UserAgentRepository repository;
    private final Clock clock;

    public UserAgentService(UserAgentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<UserAgent> list(String ownerUsername) {
        return repository.findByOwner(ownerUsername);
    }

    public Optional<UserAgent> find(String ownerUsername, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.findByIdAndOwner(id, ownerUsername);
    }

    public UserAgent create(String ownerUsername, String name, String purpose, String model) {
        String cleanName = cleanRequired(name, "name");
        String cleanPurpose = cleanRequired(purpose, "purpose");
        ensureUniqueName(ownerUsername, cleanName, null);

        Instant now = Instant.now(clock);
        UserAgent agent = new UserAgent(
                UUID.randomUUID().toString(),
                ownerUsername,
                cleanName,
                cleanPurpose,
                Prompts.agentSystemPrompt(cleanName, cleanPurpose),
                cleanOptional(model),
                now,
                now);
        try {
            repository.insert(agent);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An agent with that name already exists");
        }
        return agent;
    }

    public UserAgent update(String ownerUsername, String id, String name, String purpose, String model) {
        UserAgent existing = repository.findByIdAndOwner(id, ownerUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        String cleanName = cleanRequired(name, "name");
        String cleanPurpose = cleanRequired(purpose, "purpose");
        ensureUniqueName(ownerUsername, cleanName, id);

        UserAgent updated = new UserAgent(
                existing.id(),
                ownerUsername,
                cleanName,
                cleanPurpose,
                Prompts.agentSystemPrompt(cleanName, cleanPurpose),
                cleanOptional(model),
                existing.createdAt(),
                Instant.now(clock));

        int rows = repository.update(updated);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found");
        }
        return updated;
    }

    public void delete(String ownerUsername, String id) {
        if (!repository.delete(id, ownerUsername)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found");
        }
    }

    public String memoryScope(String ownerUsername, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return ownerUsername;
        }
        if (ownerUsername == null || ownerUsername.isBlank()) {
            return agentId;
        }
        return ownerUsername + ":" + agentId;
    }

    private void ensureUniqueName(String ownerUsername, String name, String selfId) {
        for (UserAgent agent : repository.findByOwner(ownerUsername)) {
            if (Objects.equals(agent.name(), name) && !Objects.equals(agent.id(), selfId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An agent with that name already exists");
            }
        }
    }

    private static String cleanRequired(String value, String field) {
        if (value == null || value.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static String cleanOptional(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
