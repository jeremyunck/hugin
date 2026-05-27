package com.example.agent;

import com.example.agent.model.*;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Orchestrates the cloud-agent lifecycle:
 * <ol>
 *   <li>Allocate a UUID as both the agent ID and the conversation {@code sessionId}.</li>
 *   <li>Clone the target repository into {@code $AGENT_HOME/agents/<id>/}.</li>
 *   <li>Register the cloned working tree as the session's {@link com.example.agent.tool.Workspace}.</li>
 *   <li>Run the agent loop ({@link AgentService#chatStream}) with the task as the prompt.</li>
 *   <li>Commit changes, push, and open a PR via {@link PullRequestPublisher}.</li>
 *   <li>Persist status to {@code agent.json}; clean up if requested.</li>
 * </ol>
 *
 * <p>Depends only on {@link RepositoryProvisioner}, {@link PullRequestPublisher},
 * {@link RunStore}, and {@link WorkspaceFactory} SPIs defined in {@code agent-core},
 * keeping the module boundary clean.
 */
@Service
public class CloudAgentService {

    private static final Logger log = LoggerFactory.getLogger(CloudAgentService.class);

    private final AgentService agentService;
    private final WorkspaceRegistry workspaceRegistry;
    private final WorkspaceFactory workspaceFactory;
    private final Optional<RepositoryProvisioner> provisioner;
    private final Optional<PullRequestPublisher> prPublisher;
    private final RunStore runStore;
    private final CloudAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final Path agentHome;

    /** In-memory registry of known agents, backed by RunStore. */
    private final ConcurrentHashMap<String, AgentInfo> agents = new ConcurrentHashMap<>();

    /** Semaphore enforcing maxConcurrent. */
    private Semaphore concurrencySemaphore;

    /** Tracks the number of currently running agents. */
    private int activeCount = 0;

    public CloudAgentService(
            AgentService agentService,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            Optional<RepositoryProvisioner> provisioner,
            Optional<PullRequestPublisher> prPublisher,
            Optional<RunStore> runStore,
            CloudAgentProperties properties,
            ObjectMapper objectMapper,
            @Value("${agent.home:.}") String agentHome) {
        this.agentService = agentService;
        this.workspaceRegistry = workspaceRegistry;
        this.workspaceFactory = workspaceFactory;
        this.provisioner = provisioner;
        this.prPublisher = prPublisher;
        this.runStore = runStore.orElse(null);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.agentHome = Path.of(agentHome);
        this.concurrencySemaphore = new Semaphore(properties.maxConcurrent());
    }

    @PostConstruct
    public void init() {
        this.concurrencySemaphore = new Semaphore(properties.maxConcurrent());
        log.info("CloudAgentService initialized: maxConcurrent={}", properties.maxConcurrent());

        // Reload persisted runs on startup
        if (runStore != null) {
            List<AgentInfo> persisted = runStore.findAll();
            for (AgentInfo info : persisted) {
                agents.put(info.id(), info);
            }
            if (!persisted.isEmpty()) {
                log.info("Reloaded {} agent runs from persistent store", persisted.size());
            }
        }
    }

    /**
     * Creates and starts a new cloud agent synchronously (streaming is the caller's concern).
     * Returns the agent ID immediately; callers invoke {@link #run(String, String, String, AgentStreamListener)}
     * to drive the loop and stream events.
     */
    public AgentInfo create(String repoUrl, String task, String sourceBranch, String model) {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                    "Cloud agents are disabled. Set agent.cloud.enabled=true to enable.");
        }
        if (provisioner.isEmpty()) {
            throw new IllegalStateException(
                    "No RepositoryProvisioner available. The git-CLI implementation requires "
                    + "the mcp-integration module.");
        }

        // Acquire a concurrency permit (non-blocking check)
        if (!concurrencySemaphore.tryAcquire()) {
            String msg = "Maximum concurrent cloud agents reached (" + properties.maxConcurrent()
                    + "). Try again later or cancel another run.";
            log.warn("Concurrency limit hit: {}", msg);
            throw new IllegalStateException(msg);
        }

        String agentId = UUID.randomUUID().toString();
        Path agentDir = agentHome.resolve("agents").resolve(agentId);

        // Derive branch name: <prefix>/<slug>-<short-id>
        String slug = slugify(task);
        String shortId = agentId.substring(0, 8);
        String newBranch = properties.branchPrefix() + "/" + slug + "-" + shortId;

        AgentInfo info = new AgentInfo(agentId, repoUrl, newBranch,
                AgentStatus.RUNNING, Instant.now(), task);
        agents.put(agentId, info);
        persistInfo(info);

        try {
            Files.createDirectories(agentDir);
            ProvisionedRepo repo = provisioner.get()
                    .provision(repoUrl, agentDir, sourceBranch, newBranch);

            workspaceRegistry.register(agentId, workspaceFactory.create(repo.workingTree()));
            log.info("Cloud agent {} provisioned: repo={}, branch={}, tree={}",
                    agentId, repoUrl, repo.branch(), repo.workingTree());

            return new AgentInfo(agentId, repoUrl, repo.branch(),
                    AgentStatus.RUNNING, info.createdAt(), task);
        } catch (Exception e) {
            concurrencySemaphore.release();
            AgentInfo failed = new AgentInfo(agentId, repoUrl, newBranch,
                    AgentStatus.FAILED, info.createdAt(), task, e.getMessage());
            agents.put(agentId, failed);
            persistInfo(failed);
            workspaceRegistry.unregister(agentId);
            throw new RuntimeException("Failed to provision agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Runs the agent loop for an already-created agent and reports progress via {@code listener}.
     * After the agent loop finishes, commits changes, pushes the branch, and opens a PR.
     * Updates agent status to DONE or FAILED on completion. Releases the concurrency slot.
     */
    public AgentResponse run(String agentId, String model, String baseBranch, AgentStreamListener listener) {
        AgentInfo info = agents.get(agentId);
        if (info == null) {
            throw new NoSuchElementException("Agent not found: " + agentId);
        }

        Path agentDir = agentHome.resolve("agents").resolve(agentId);
        try {
            AgentRequest request = new AgentRequest(info.task(), model, agentId, null);
            AgentResponse response = agentService.chatStream(request, listener);

            // Post-run: commit, push, and open PR
            String prUrl = null;
            if (prPublisher.isPresent() && info.status() == AgentStatus.RUNNING) {
                try {
                    Path workingTree = workspaceRegistry.resolve(agentId).root();
                    String title = "feat: " + info.task();
                    prUrl = prPublisher.get().publish(workingTree, info.repoUrl(),
                            info.branch(), baseBranch, title);
                    if (prUrl != null) {
                        listener.onPrOpened(prUrl);
                    }
                } catch (Exception e) {
                    log.warn("PR workflow failed for agent {}: {}", agentId, e.getMessage());
                }
            }

            AgentInfo done = new AgentInfo(agentId, info.repoUrl(), info.branch(),
                    AgentStatus.DONE, info.createdAt(), info.task(), null, prUrl);
            agents.put(agentId, done);
            persistInfo(done);

            if (properties.cleanupOnComplete()) {
                deleteInternal(agentId);
            }
            return response;
        } catch (Exception e) {
            AgentInfo failed = new AgentInfo(agentId, info.repoUrl(), info.branch(),
                    AgentStatus.FAILED, info.createdAt(), info.task(), e.getMessage());
            agents.put(agentId, failed);
            persistInfo(failed);
            throw e;
        } finally {
            concurrencySemaphore.release();
            workspaceRegistry.unregister(agentId);
        }
    }

    public List<AgentInfo> list() {
        return List.copyOf(agents.values());
    }

    public Optional<AgentInfo> get(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** Stops the agent (if running), removes its workspace registration, and deletes its directory. */
    public void delete(String agentId) {
        workspaceRegistry.unregister(agentId);
        agents.remove(agentId);
        if (runStore != null) {
            runStore.deleteById(agentId);
        }
        concurrencySemaphore.release();
        Path agentDir = agentHome.resolve("agents").resolve(agentId);
        try {
            deleteRecursively(agentDir);
        } catch (IOException e) {
            log.warn("Could not delete agent directory {}: {}", agentDir, e.getMessage());
        }
    }

    private void deleteInternal(String agentId) {
        agents.remove(agentId);
        if (runStore != null) {
            runStore.deleteById(agentId);
        }
        Path agentDir = agentHome.resolve("agents").resolve(agentId);
        try {
            deleteRecursively(agentDir);
        } catch (IOException e) {
            log.warn("Could not delete agent directory {}: {}", agentDir, e.getMessage());
        }
    }

    private void persistInfo(AgentInfo info) {
        if (runStore != null) {
            runStore.save(info);
        }
    }

    private static String slugify(String task) {
        String slug = task.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 30 ? slug.substring(0, 30) : slug.isEmpty() ? "task" : slug;
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