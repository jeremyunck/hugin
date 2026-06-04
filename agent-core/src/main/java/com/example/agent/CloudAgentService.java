package com.example.agent;

import com.example.agent.model.*;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the cloud-agent lifecycle:
 * <ol>
 *   <li>Allocate a UUID as both the agent ID and the conversation {@code sessionId}.</li>
 *   <li>Clone the target repository into {@code $AGENT_HOME/agents/<id>/}.</li>
 *   <li>Register the cloned working tree as the session's {@link com.example.agent.tool.Workspace}.</li>
 *   <li>Run the agent loop ({@link AgentService#chatStream}) with the task as the prompt.</li>
 *   <li>Persist status to {@code agent.json}; clean up if requested.</li>
 * </ol>
 *
 * <p>Depends only on {@link RepositoryProvisioner} and {@link WorkspaceFactory} SPIs defined in
 * {@code agent-core}, keeping the module boundary clean.
 */
@Service
public class CloudAgentService {

    private static final Logger log = LoggerFactory.getLogger(CloudAgentService.class);

    private final AgentService agentService;
    private final WorkspaceRegistry workspaceRegistry;
    private final WorkspaceFactory workspaceFactory;
    private final Optional<RepositoryProvisioner> provisioner;
    private final Optional<PullRequestPublisher> pullRequestPublisher;
    private final CloudAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final Path agentHome;
    private final Semaphore concurrencyGate;

    /** In-memory registry of known agents (supplemented by agent.json on disk). */
    private final ConcurrentHashMap<String, AgentInfo> agents = new ConcurrentHashMap<>();

    public CloudAgentService(
            AgentService agentService,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            Optional<RepositoryProvisioner> provisioner,
            Optional<PullRequestPublisher> pullRequestPublisher,
            CloudAgentProperties properties,
            ObjectMapper objectMapper,
            @Value("${agent.home:.}") String agentHome) {
        this.agentService = agentService;
        this.workspaceRegistry = workspaceRegistry;
        this.workspaceFactory = workspaceFactory;
        this.provisioner = provisioner;
        this.pullRequestPublisher = pullRequestPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.agentHome = Path.of(agentHome);
        this.concurrencyGate = new Semaphore(properties.maxConcurrent(), true);
    }

    /** Result of a completed cloud-agent run, including any published PR URL. */
    public record RunResult(AgentResponse response, Optional<String> pullRequestUrl, boolean changed) {}

    /**
     * Creates and starts a new cloud agent synchronously (streaming is the caller's concern).
     * Returns the agent ID immediately; callers invoke {@link #run(String, AgentStreamListener)}
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
        if (!concurrencyGate.tryAcquire()) {
            throw new IllegalStateException(
                    "Cloud agent concurrency limit reached (agent.cloud.maxConcurrent="
                            + properties.maxConcurrent() + ")");
        }

        String agentId = UUID.randomUUID().toString();
        AgentSandbox sandbox = sandbox(agentId);
        Path agentDir = sandbox.root();

        // Derive branch name: <prefix>/<slug>-<short-id>
        String slug = slugify(task);
        String shortId = agentId.substring(0, 8);
        String newBranch = properties.branchPrefix() + "/" + slug + "-" + shortId;

        AgentInfo info = new AgentInfo(agentId, repoUrl, sourceBranch, newBranch,
                AgentStatus.RUNNING, Instant.now(), task);
        agents.put(agentId, info);
        persistAgentInfo(agentDir, info);

        try {
            Files.createDirectories(sandbox.repoRoot());
            Files.createDirectories(sandbox.homeDir());
            Files.createDirectories(sandbox.tmpDir());
            ProvisionedRepo repo = provisioner.get()
                    .provision(repoUrl, sandbox, sourceBranch, newBranch);

            workspaceRegistry.register(agentId, workspaceFactory.create(repo.workingTree()));
            log.info("Cloud agent {} provisioned: repo={}, branch={}, tree={}",
                    agentId, repoUrl, repo.branch(), repo.workingTree());

            return new AgentInfo(agentId, repoUrl, sourceBranch, repo.branch(),
                    AgentStatus.RUNNING, info.createdAt(), task);
        } catch (Exception e) {
            concurrencyGate.release();
            AgentInfo failed = new AgentInfo(agentId, repoUrl, sourceBranch, newBranch,
                    AgentStatus.FAILED, info.createdAt(), task, e.getMessage());
            agents.put(agentId, failed);
            persistAgentInfo(agentDir, failed);
            workspaceRegistry.unregister(agentId);
            throw new RuntimeException("Failed to provision agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Runs the agent loop for an already-created agent and reports progress via {@code listener}.
     * Updates agent status to DONE or FAILED on completion.
     */
    public RunResult run(String agentId, String model, AgentStreamListener listener) {
        AgentInfo info = agents.get(agentId);
        if (info == null) {
            throw new NoSuchElementException("Agent not found: " + agentId);
        }

        Path agentDir = sandbox(agentId).root();
        try {
            AgentRequest request = new AgentRequest(info.task(), model, agentId);
            AgentResponse response = agentService.chatStream(request, listener);
            Path workingTree = workspaceRegistry.resolve(info.id()).root();
            boolean changed = hasChanges(workingTree);
            if (!changed) {
                log.info("Cloud agent {} made no changes", info.id());
            }
            Optional<String> prUrl = changed
                    ? finalizeRun(info, agentDir, workingTree)
                    : Optional.empty();

            AgentInfo done = new AgentInfo(agentId, info.repoUrl(), info.baseBranch(), info.branch(),
                    AgentStatus.DONE, info.createdAt(), info.task(), null,
                    prUrl.orElse(null));
            agents.put(agentId, done);
            persistAgentInfo(agentDir, done);

            if (properties.cleanupOnComplete()) {
                delete(agentId);
            }
            return new RunResult(response, prUrl, changed);
        } catch (Exception e) {
            AgentInfo failed = new AgentInfo(agentId, info.repoUrl(), info.baseBranch(), info.branch(),
                    AgentStatus.FAILED, info.createdAt(), info.task(), e.getMessage());
            agents.put(agentId, failed);
            persistAgentInfo(agentDir, failed);
            throw new RuntimeException(e);
        } finally {
            workspaceRegistry.unregister(agentId);
            concurrencyGate.release();
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
        Path agentDir = agentHome.resolve("agents").resolve(agentId);
        try {
            deleteRecursively(agentDir);
        } catch (IOException e) {
            log.warn("Could not delete agent directory {}: {}", agentDir, e.getMessage());
        }
    }

    private void persistAgentInfo(Path agentDir, AgentInfo info) {
        try {
            Files.createDirectories(agentDir);
            Path file = agentDir.resolve("agent.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), info);
        } catch (Exception e) {
            log.warn("Could not persist agent.json for {}: {}", info.id(), e.getMessage());
        }
    }

    private Optional<String> finalizeRun(AgentInfo info, Path agentDir, Path workingTree) throws Exception {
        runGit(workingTree, "git add -A");
        String commitMessage = buildCommitMessage(info);
        runGit(workingTree, "git commit -m " + shellQuote(commitMessage));
        runGit(workingTree, "git push origin " + shellQuote(info.branch()));

        if (pullRequestPublisher.isEmpty()) {
            log.warn("No PullRequestPublisher available; branch was pushed but no PR was opened");
            return Optional.empty();
        }

        String title = buildPullRequestTitle(info);
        String body = buildPullRequestBody(info);
        Optional<String> prUrl = pullRequestPublisher.get().publish(
                info.repoUrl(), workingTree, info.branch(), info.baseBranch(), title, body);
        prUrl.ifPresent(url -> log.info("Cloud agent {} opened PR {}", info.id(), url));
        return prUrl;
    }

    private boolean hasChanges(Path workingTree) throws IOException, InterruptedException {
        String output = runGitCapture(workingTree, "git status --porcelain");
        return !output.isBlank();
    }

    private void runGit(Path workingTree, String command) throws IOException, InterruptedException {
        String output = runGitCapture(workingTree, command);
        if (!output.isBlank()) {
            log.debug("git output: {}", output);
        }
    }

    private String runGitCapture(Path workingTree, String command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-lc", command);
        builder.directory(workingTree.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + command);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git command failed (" + process.exitValue() + "): " + output);
        }
        return output;
    }

    private AgentSandbox sandbox(String agentId) {
        Path root = agentHome.resolve("agents").resolve(agentId);
        return new AgentSandbox(root, root.resolve("repo"), root.resolve("home"), root.resolve("tmp"));
    }

    private static String buildCommitMessage(AgentInfo info) {
        String summary = firstLine(info.task());
        return "Hugin: " + summary;
    }

    private static String buildPullRequestTitle(AgentInfo info) {
        return "Hugin: " + firstLine(info.task());
    }

    private static String buildPullRequestBody(AgentInfo info) {
        return "Automated change generated by Hugin.\n\n"
                + "Task:\n" + info.task() + "\n";
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "automated changes";
        }
        String first = text.strip().split("\\R", 2)[0].trim();
        return first.length() > 72 ? first.substring(0, 72) : first;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
