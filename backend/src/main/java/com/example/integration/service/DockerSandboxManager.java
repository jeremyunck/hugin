package com.example.integration.service;

import com.example.agent.model.FileNode;
import com.example.agent.model.SandboxInfo;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.example.agent.tool.WorkspaceRehydrator;
import com.example.integration.github.GitHubAppService;
import com.example.integration.sandbox.SandboxProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Docker-backed implementation of {@link SandboxRuntime} plus the lifecycle management behind the
 * {@code /api/sandboxes} endpoints.
 *
 * <p>Each sandbox is a long-lived container started with the per-session working directory
 * bind-mounted at the same absolute path on host and container, so the agent's file tools (which
 * operate on the host bind-mount) and {@code run_bash} (which {@code docker exec}s into the
 * container) share one consistent workspace path. The host directory is registered with the
 * {@link WorkspaceRegistry} under the sandbox id so the agent loop confines file access to it.
 */
@Service
public class DockerSandboxManager implements SandboxRuntime, WorkspaceRehydrator {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxManager.class);
    private static final String HOST_FALLBACK_PREFIX = "host-fallback-";
    /** containerName marker for a GitHub project workspace (host-backed, persistent, no container). */
    private static final String GITHUB_WORKSPACE_PREFIX = "github-workspace-";

    private final SandboxProperties properties;
    private final WorkspaceRegistry workspaceRegistry;
    private final WorkspaceFactory workspaceFactory;
    private final Optional<GitHubWorkspaceStore> githubWorkspaceStore;
    private final Optional<GitHubAppService> github;
    private final Path sandboxesHome;
    private final Path githubWorkspacesHome;

    private final ConcurrentHashMap<String, LiveSandbox> sandboxes = new ConcurrentHashMap<>();

    public DockerSandboxManager(
            SandboxProperties properties,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            Optional<GitHubWorkspaceStore> githubWorkspaceStore,
            Optional<GitHubAppService> github,
            @Value("${agent.home:${user.home}/.hugin}") String agentHome) {
        this.properties = properties;
        this.workspaceRegistry = workspaceRegistry;
        this.workspaceFactory = workspaceFactory;
        this.githubWorkspaceStore = githubWorkspaceStore;
        this.github = github;
        this.sandboxesHome = Path.of(agentHome).resolve("sandboxes");
        this.githubWorkspacesHome = Path.of(agentHome).resolve("workspace");
    }

    /**
     * Convenience constructor for tests / hosts without GitHub-workspace persistence (Docker and
     * host-fallback sandboxes only; no rehydration).
     */
    public DockerSandboxManager(
            SandboxProperties properties,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            String agentHome) {
        this(properties, workspaceRegistry, workspaceFactory, Optional.empty(), Optional.empty(), agentHome);
    }

    /** Wires this manager in as the registry's rehydrator so resumed chats restore their workspace. */
    @PostConstruct
    void registerRehydrator() {
        workspaceRegistry.setRehydrator(this);
    }

    /** Creates and starts a new sandbox container, returning its metadata. */
    public SandboxInfo create(String imageOverride) {
        return createSandbox(imageOverride);
    }

    /**
     * Creates a persistent, host-backed workspace whose root <em>is</em> a clone of the given GitHub
     * repository. The repository is cloned into {@code $AGENT_HOME/workspace/<repo>-<short-id>/} (a
     * stable per-chat directory, e.g. {@code ~/.hugin/workspace/hugin-1a2b3c4d/}) so the agent and the
     * file tree see the repository's files at the workspace root.
     *
     * <p>Unlike the generic Docker sandboxes, a GitHub project workspace does not start a container:
     * all tools run on the host from inside the clone. The binding is persisted so a chat resumed long
     * after creation rehydrates to the same directory (see {@link #rehydrate(String)}), re-cloning when
     * the directory was removed. The agent's file and shell tools remain confined to this directory.
     *
     * @param repoFullName the {@code owner/repo} being cloned; used to name the workspace and to give
     *                     the agent repository context
     */
    public SandboxInfo createGitHubRepoSandbox(
            String imageOverride,
            String cloneUrl,
            String repoFullName,
            String branch,
            String accessToken,
            BugReportCatalogService.StoredBugReport bugReport) {
        String id = UUID.randomUUID().toString();
        Path workspace;
        try {
            Path dir = githubWorkspacesHome.resolve(repoLeafName(repoFullName) + "-" + id.substring(0, 8));
            Files.createDirectories(dir);
            workspace = dir.toRealPath();
        } catch (IOException e) {
            throw new RuntimeException("Could not create GitHub workspace directory: " + e.getMessage(), e);
        }

        SandboxInfo info = new SandboxInfo(id, GITHUB_WORKSPACE_PREFIX + id,
                imageOverride == null || imageOverride.isBlank() ? "host" : imageOverride,
                SandboxInfo.RUNNING, Instant.now(), workspace.toString());
        try {
            cloneRepository(workspace, cloneUrl, branch, accessToken, bugReport);
            workspaceRegistry.register(id, workspaceFactory.create(workspace));
            workspaceRegistry.registerGithubRepo(id, repoFullName);
            sandboxes.put(id, new LiveSandbox(info, false, true));
            githubWorkspaceStore.ifPresent(store -> store.save(
                    new GitHubWorkspaceStore.Record(id, repoFullName, branch, cloneUrl, info.workspace())));
            log.info("Created GitHub project workspace {} (repo={}, branch={}, dir={})",
                    id, repoFullName, branch, workspace);
            return info;
        } catch (RuntimeException e) {
            workspaceRegistry.unregister(id);
            deleteQuietly(workspace);
            throw e;
        }
    }

    /**
     * Restores a GitHub project workspace whose in-memory registration was lost (e.g. after a
     * restart) from its persisted binding, re-cloning the repository when the directory is gone. Used
     * by {@link WorkspaceRegistry} on a cache miss so a resumed chat resolves to its repository clone
     * instead of the default host workspace.
     */
    @Override
    public synchronized boolean rehydrate(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank() || sandboxes.containsKey(sandboxId)) {
            return sandboxes.containsKey(sandboxId);
        }
        if (githubWorkspaceStore.isEmpty()) {
            return false;
        }
        Optional<GitHubWorkspaceStore.Record> stored = githubWorkspaceStore.get().find(sandboxId);
        if (stored.isEmpty()) {
            return false;
        }
        GitHubWorkspaceStore.Record record = stored.get();
        Path workspace = Path.of(record.workspacePath());
        boolean hasClone = Files.isDirectory(workspace.resolve(".git"));
        if (!hasClone) {
            try {
                Files.createDirectories(workspace);
                deleteContents(workspace);
                String token = github.flatMap(GitHubAppService::installationToken).orElse(null);
                cloneRepository(workspace, record.cloneUrl(), record.branch(), token, null);
                log.info("Re-cloned GitHub project workspace {} (repo={}) into {}",
                        sandboxId, record.repoFullName(), workspace);
            } catch (RuntimeException | IOException e) {
                log.warn("Could not re-clone GitHub workspace {} ({}): {}",
                        sandboxId, record.repoFullName(), e.getMessage());
                return false;
            }
        }
        SandboxInfo info = new SandboxInfo(sandboxId, GITHUB_WORKSPACE_PREFIX + sandboxId,
                "host", SandboxInfo.RUNNING, Instant.now(), workspace.toString());
        workspaceRegistry.register(sandboxId, workspaceFactory.create(workspace));
        workspaceRegistry.registerGithubRepo(sandboxId, record.repoFullName());
        sandboxes.put(sandboxId, new LiveSandbox(info, false, true));
        log.info("Rehydrated GitHub project workspace {} (repo={}, dir={})",
                sandboxId, record.repoFullName(), workspace);
        return true;
    }

    /**
     * Derives a safe, single-segment workspace directory name from an {@code owner/repo} (or bare
     * repo) string, falling back to {@code "workspace"} when nothing usable remains.
     */
    private static String repoLeafName(String repoFullName) {
        if (repoFullName == null || repoFullName.isBlank()) {
            return "workspace";
        }
        String leaf = repoFullName.trim();
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) {
            leaf = leaf.substring(slash + 1);
        }
        leaf = leaf.replaceAll("[^A-Za-z0-9._-]", "-");
        while (leaf.startsWith(".")) {
            leaf = leaf.substring(1);
        }
        return leaf.isBlank() ? "workspace" : leaf;
    }

    private SandboxInfo createSandbox(String imageOverride) {
        return createSandbox(imageOverride, "workspace");
    }

    private SandboxInfo createSandbox(String imageOverride, String workspaceDirName) {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                    "Sandboxes are disabled. Set agent.sandbox.enabled=true to enable.");
        }
        if (sandboxes.size() >= properties.maxSandboxes()) {
            throw new IllegalStateException(
                    "Sandbox limit reached (agent.sandbox.max-sandboxes=" + properties.maxSandboxes() + ")");
        }
        String image = (imageOverride != null && !imageOverride.isBlank())
                ? imageOverride : properties.image();
        String id = UUID.randomUUID().toString();
        String containerName = properties.containerPrefix() + id;

        Path workspace;
        try {
            Path dir = sandboxesHome.resolve(id).resolve(workspaceDirName);
            Files.createDirectories(dir);
            workspace = dir.toRealPath();
        } catch (IOException e) {
            throw new RuntimeException("Could not create sandbox workspace directory: " + e.getMessage(), e);
        }

        List<String> runCmd = new ArrayList<>(List.of(
                properties.dockerBin(), "run", "-d", "--name", containerName,
                "-v", workspace + ":" + workspace,
                "-w", workspace.toString()));
        if (!properties.network().isBlank()) {
            runCmd.add("--network");
            runCmd.add(properties.network());
        }
        runCmd.add(image);
        // Keep the container alive so we can exec into it for the lifetime of the session.
        runCmd.addAll(List.of("tail", "-f", "/dev/null"));

        ProcessResult result = runProcess(runCmd, properties.startTimeout());
        if (dockerCliUnavailable(result)) {
            workspaceRegistry.register(id, workspaceFactory.create(workspace));
            SandboxInfo info = new SandboxInfo(id, HOST_FALLBACK_PREFIX + id, image, SandboxInfo.RUNNING,
                    Instant.now(), workspace.toString());
            sandboxes.put(id, new LiveSandbox(info, false, false));
            log.warn("Docker CLI unavailable; created host-fallback sandbox {} in workspace {}",
                    id, workspace);
            return info;
        }
        if (result.timedOut() || result.exitCode() != 0) {
            deleteQuietly(workspace.getParent());
            throw new RuntimeException("Failed to start sandbox container (image=" + image + "): "
                    + (result.timedOut() ? "docker run timed out" : result.output()));
        }

        workspaceRegistry.register(id, workspaceFactory.create(workspace));
        SandboxInfo info = new SandboxInfo(id, containerName, image, SandboxInfo.RUNNING,
                Instant.now(), workspace.toString());
        sandboxes.put(id, new LiveSandbox(info, true, false));
        log.info("Created sandbox {} (container={}, image={}, workspace={})",
                id, containerName, image, workspace);
        return info;
    }

    private void cloneRepository(
            Path workspace,
            String cloneUrl,
            String branch,
            String accessToken,
            BugReportCatalogService.StoredBugReport bugReport) {
        // Clone directly into the workspace root so the repository's files are the workspace root
        // (no nested workspace/<repo> directory). The workspace directory already exists and is
        // empty, which git clone accepts as the destination.
        List<String> command = new ArrayList<>(List.of("git", "clone", "--single-branch"));
        if (branch != null && !branch.isBlank()) {
            command.addAll(List.of("--branch", branch));
        }
        command.addAll(List.of(cloneUrl, workspace.toString()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.getParent().toFile());
        builder.redirectErrorStream(true);
        configureGitCredentials(builder, accessToken);

        ProcessResult result = runHostProcess(builder, properties.startTimeout());
        if (result.timedOut() || result.exitCode() != 0) {
            throw new RuntimeException("Failed to clone GitHub repository into sandbox: "
                    + (result.timedOut() ? "git clone timed out" : result.output()));
        }
        if (bugReport != null) {
            importBugReport(workspace, bugReport);
        }
    }

    private void importBugReport(Path repoRoot, BugReportCatalogService.StoredBugReport bugReport) {
        String relativePath = bugReport.relativePath();
        if (relativePath == null || relativePath.isBlank()) {
            relativePath = "bug-reports/imported/" + bugReport.id() + ".txt";
        }
        Path destination = repoRoot.resolve(relativePath).normalize();
        if (!destination.startsWith(repoRoot)) {
            throw new RuntimeException("Bug report path resolves outside the repository workspace.");
        }
        try {
            Files.createDirectories(destination.getParent());
            Files.writeString(
                    destination,
                    bugReport.content(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to stage bug report in repository sandbox: " + e.getMessage(), e);
        }
    }

    public List<SandboxInfo> list() {
        return sandboxes.values().stream().map(LiveSandbox::info).toList();
    }

    public Optional<SandboxInfo> get(String id) {
        if (id != null && !sandboxes.containsKey(id)) {
            rehydrate(id);
        }
        return Optional.ofNullable(sandboxes.get(id)).map(LiveSandbox::info);
    }

    /** Maximum directory depth walked when building a workspace file tree. */
    private static final int MAX_TREE_DEPTH = 8;
    /** Maximum entries returned per directory, to bound the response for large workspaces. */
    private static final int MAX_ENTRIES_PER_DIR = 500;

    /**
     * Returns the workspace file tree for a sandbox (relative to its workspace root), or empty when
     * the sandbox is unknown. Directories listed in {@link Workspace#IGNORED_DIRECTORIES} are skipped,
     * the walk is depth- and width-bounded, and directories sort before files (each alphabetically).
     */
    public Optional<List<FileNode>> listFiles(String id) {
        if (id != null && !sandboxes.containsKey(id)) {
            rehydrate(id);
        }
        LiveSandbox sandbox = sandboxes.get(id);
        if (sandbox == null) {
            return Optional.empty();
        }
        Path root = Path.of(sandbox.info().workspace());
        return Optional.of(buildChildren(root, root, 0));
    }

    private List<FileNode> buildChildren(Path root, Path dir, int depth) {
        if (depth >= MAX_TREE_DEPTH || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(entries::add);
        } catch (IOException e) {
            log.debug("Could not list sandbox directory {}: {}", dir, e.getMessage());
            return List.of();
        }
        entries.sort(Comparator
                .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString().toLowerCase()));

        List<FileNode> nodes = new ArrayList<>();
        for (Path entry : entries) {
            if (nodes.size() >= MAX_ENTRIES_PER_DIR) {
                break;
            }
            String name = entry.getFileName().toString();
            String relative = root.relativize(entry).toString();
            if (Files.isDirectory(entry)) {
                if (Workspace.IGNORED_DIRECTORIES.contains(name)) {
                    continue;
                }
                nodes.add(FileNode.directory(name, relative, buildChildren(root, entry, depth + 1)));
            } else {
                long size;
                try {
                    size = Files.size(entry);
                } catch (IOException e) {
                    size = 0L;
                }
                nodes.add(FileNode.file(name, relative, size));
            }
        }
        return nodes;
    }

    /** Stops and removes the sandbox container and deletes its workspace. */
    public void delete(String id) {
        if (id != null && !sandboxes.containsKey(id)) {
            // Rehydrate first so deleting a chat resumed after a restart still removes its on-disk
            // clone and persisted binding, not just the (already absent) in-memory entry.
            rehydrate(id);
        }
        LiveSandbox sandbox = sandboxes.remove(id);
        workspaceRegistry.unregister(id);
        githubWorkspaceStore.ifPresent(store -> store.delete(id));
        if (sandbox == null) {
            return;
        }
        SandboxInfo info = sandbox.info();
        if (sandbox.dockerBacked()) {
            ProcessResult result = runProcess(
                    List.of(properties.dockerBin(), "rm", "-f", info.containerName()),
                    Duration.ofSeconds(30));
            if (result.exitCode() != 0 && !result.timedOut()) {
                log.warn("Could not remove sandbox container {}: {}", info.containerName(), result.output());
            }
        }
        Path workspace = Path.of(info.workspace());
        if (sandbox.persistentGithub()) {
            // The clone lives directly under $AGENT_HOME/workspace/, whose parent is shared across all
            // GitHub chats — delete only this chat's own directory.
            deleteQuietly(workspace);
        } else {
            Path sandboxRoot = workspace.getParent();
            deleteQuietly(sandboxRoot != null ? sandboxRoot : workspace);
        }
        log.info("Deleted sandbox {}", id);
    }

    @Override
    public boolean isActive(String sandboxId) {
        if (sandboxId == null) {
            return false;
        }
        if (sandboxes.containsKey(sandboxId)) {
            return true;
        }
        return rehydrate(sandboxId);
    }

    @Override
    public ExecResult exec(String sandboxId, String command, Duration timeout) {
        if (sandboxId != null && !sandboxes.containsKey(sandboxId)) {
            rehydrate(sandboxId);
        }
        LiveSandbox sandbox = sandboxes.get(sandboxId);
        if (sandbox == null) {
            throw new IllegalStateException("Unknown sandbox: " + sandboxId);
        }
        SandboxInfo info = sandbox.info();
        Duration effective = (timeout != null) ? timeout : properties.execTimeout();
        if (!sandbox.dockerBacked()) {
            return runHostCommand(Path.of(info.workspace()), command, effective);
        }
        List<String> execCmd = List.of(
                properties.dockerBin(), "exec", "-w", info.workspace(),
                info.containerName(), "/bin/sh", "-c", command);
        ProcessResult result = runProcess(execCmd, effective);
        return new ExecResult(result.exitCode(), result.output(), result.timedOut());
    }

    @PreDestroy
    public void shutdown() {
        for (String id : List.copyOf(sandboxes.keySet())) {
            try {
                LiveSandbox sandbox = sandboxes.get(id);
                if (sandbox != null && sandbox.persistentGithub()) {
                    // Persistent GitHub workspaces must survive a restart: drop the in-memory entry
                    // only, leaving the clone on disk and its binding in the store to rehydrate later.
                    sandboxes.remove(id);
                    workspaceRegistry.unregister(id);
                    continue;
                }
                delete(id);
            } catch (Exception e) {
                log.warn("Error cleaning up sandbox {} on shutdown: {}", id, e.getMessage());
            }
        }
    }

    private boolean dockerCliUnavailable(ProcessResult result) {
        return result.exitCode() == -1
                && !result.timedOut()
                && result.output().startsWith("Could not run '" + properties.dockerBin() + "'");
    }

    private ExecResult runHostCommand(Path workspace, String command, Duration timeout) {
        // Development-only compatibility path when no container runtime exists. Commands still run
        // in a per-session workspace and remain subject to the normal wall-clock timeout, but this
        // is not a substitute for container isolation on a multi-tenant host.
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new ExecResult(-1, "Could not run host fallback shell: " + e.getMessage(), false);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // stream closed; keep what we have
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return new ExecResult(-1, output.toString().strip(), true);
            }
            reader.join(2000);
            return new ExecResult(process.exitValue(), output.toString().strip(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ExecResult(-1, output.toString().strip(), false);
        }
    }

    private record ProcessResult(int exitCode, String output, boolean timedOut) {}

    /**
     * A live sandbox entry. {@code dockerBacked} distinguishes a real container from a host-execution
     * fallback; {@code persistentGithub} marks a GitHub project workspace whose clone lives directly
     * under {@code $AGENT_HOME/workspace/<dir>} and must be deleted as the directory itself (its parent
     * is shared across chats), with its persisted binding removed too.
     */
    private record LiveSandbox(SandboxInfo info, boolean dockerBacked, boolean persistentGithub) {}

    private void configureGitCredentials(ProcessBuilder builder, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        var env = builder.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "credential.helper");
        env.put("GIT_CONFIG_VALUE_0", "!f() { echo username=x-access-token; echo password=$GITHUB_TOKEN; }; f");
        env.put("GITHUB_TOKEN", accessToken);
    }

    private ProcessResult runHostProcess(ProcessBuilder builder, Duration timeout) {
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new ProcessResult(-1, "Could not run '" + builder.command().get(0) + "': " + e.getMessage(), false);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // stream closed; keep what we have
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return new ProcessResult(-1, output.toString().strip(), true);
            }
            reader.join(2000);
            return new ProcessResult(process.exitValue(), output.toString().strip(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessResult(-1, output.toString().strip(), false);
        }
    }

    /** Runs a host process (the docker CLI), capturing combined stdout/stderr. */
    private ProcessResult runProcess(List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new ProcessResult(-1, "Could not run '" + command.get(0) + "': " + e.getMessage(), false);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // stream closed; keep what we have
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return new ProcessResult(-1, output.toString().strip(), true);
            }
            reader.join(2000);
            return new ProcessResult(process.exitValue(), output.toString().strip(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessResult(-1, output.toString().strip(), false);
        }
    }

    /** Removes everything under {@code dir} (but not {@code dir} itself), e.g. to empty a stale clone. */
    private static void deleteContents(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(DockerSandboxManager::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }
}
