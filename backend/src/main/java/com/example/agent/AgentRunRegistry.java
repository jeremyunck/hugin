package com.example.agent;

import com.example.agent.model.AgentRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRunRegistry {

    public record ActiveRun(
            String id,
            String owner,
            String sessionId,
            String agentId,
            String prompt,
            String model,
            String sandboxId,
            Instant startedAt,
            boolean disconnected,
            boolean cancellationRequested) {}

    private record Entry(ActiveRun summary, Thread worker) {}

    private final Map<String, Entry> active = new ConcurrentHashMap<>();

    public String register(String owner, AgentRequest request, String model, Thread worker) {
        String id = UUID.randomUUID().toString();
        register(id, owner, request, model, worker);
        return id;
    }

    public String register(String id, String owner, AgentRequest request, String model, Thread worker) {
        ActiveRun summary = new ActiveRun(
                id,
                owner,
                request == null ? null : request.sessionId(),
                request == null ? null : request.agentId(),
                previewPrompt(request == null ? null : request.prompt()),
                model,
                request == null ? null : request.sandboxId(),
                Instant.now(),
                false,
                false);
        active.put(id, new Entry(summary, worker));
        return id;
    }

    public void markDisconnected(String id) {
        active.computeIfPresent(id, (key, entry) -> new Entry(
                new ActiveRun(
                        entry.summary.id(),
                        entry.summary.owner(),
                        entry.summary.sessionId(),
                        entry.summary.agentId(),
                        entry.summary.prompt(),
                        entry.summary.model(),
                        entry.summary.sandboxId(),
                        entry.summary.startedAt(),
                        true,
                        entry.summary.cancellationRequested()),
                entry.worker()));
    }

    public List<ActiveRun> list(String owner) {
        return active.values().stream()
                .map(Entry::summary)
                .filter(run -> run.owner().equals(owner))
                .sorted(Comparator.comparing(ActiveRun::startedAt).reversed())
                .toList();
    }

    public boolean cancel(String owner, String id) {
        Entry entry = active.get(id);
        if (entry == null || !entry.summary.owner().equals(owner)) {
            return false;
        }
        // Flip the run to cancellation-requested and interrupt its worker atomically with the
        // presence check. Holding the map bin's lock here guarantees unregister() (which also
        // mutates the map) has not run yet, so the worker is still on this run -- the stream
        // executor pools its threads, and interrupting after a run has been unregistered could
        // otherwise disrupt an unrelated run that has since reused the thread. computeIfPresent
        // returns null if the run already finished between the lookup above and here.
        return active.computeIfPresent(id, (key, current) -> {
            Thread worker = current.worker();
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            }
            return new Entry(
                    new ActiveRun(
                            current.summary.id(),
                            current.summary.owner(),
                            current.summary.sessionId(),
                            current.summary.agentId(),
                            current.summary.prompt(),
                            current.summary.model(),
                            current.summary.sandboxId(),
                            current.summary.startedAt(),
                            current.summary.disconnected(),
                            true),
                    worker);
        }) != null;
    }

    public void unregister(String id) {
        active.remove(id);
    }

    private static String previewPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        String normalized = prompt.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80).trim() + "...";
    }
}
