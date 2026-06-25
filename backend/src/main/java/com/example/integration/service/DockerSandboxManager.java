package com.example.integration.service;

import com.example.agent.BundledSkills;
import com.example.agent.model.FileNode;
import com.example.agent.model.SandboxInfo;
import com.example.agent.sandbox.FileEntry;
import com.example.agent.sandbox.FileResult;
import com.example.agent.sandbox.RepositoryConfig;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.sandbox.SandboxSession;
import com.example.agent.sandbox.WorkspaceContext;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
@Primary
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
    private final Optional<SandboxSessionService> projectSessions;
    private final Optional<DockerSandboxRuntime> projectRuntime;
    private final Path sandboxesHome;

    private final ConcurrentHashMap<String, LiveSandbox> sandboxes = new ConcurrentHashMap<>();
    /** In-memory cache of isolated project-chat sessions registered in this process (by sandbox id). */
    private final ConcurrentHashMap<String, ProjectSandbox> projectSandboxes = new ConcurrentHashMap<>();

    @Autowired
    public DockerSandboxManager(
            SandboxProperties properties,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            Optional<GitHubWorkspaceStore> githubWorkspaceStore,
            Optional<GitHubAppService> github,
            Optional<SandboxSessionService> projectSessions,
            Optional<DockerSandboxRuntime> projectRuntime,
            @Value("${agent.home:${user.home}/.hugin}") String agentHome) {
        this.properties = properties;
        this.workspaceRegistry = workspaceRegistry;
        this.workspaceFactory = workspaceFactory;
        this.githubWorkspaceStore = githubWorkspaceStore;
        this.github = github;
        this.projectSessions = projectSessions;
        this.projectRuntime = projectRuntime;
        this.sandboxesHome = Path.of(agentHome).resolve("sandboxes");
    }

    /**
     * Convenience constructor for tests / hosts with GitHub-workspace persistence but no isolated
     * project-sandbox runtime.
     */
    public DockerSandboxManager(
            SandboxProperties properties,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            Optional<GitHubWorkspaceStore> githubWorkspaceStore,
            Optional<GitHubAppService> github,
            String agentHome) {
        this(properties, workspaceRegistry, workspaceFactory, githubWorkspaceStore, github,
                Optional.empty(), Optional.empty(), agentHome);
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
        this(properties, workspaceRegistry, workspaceFactory, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), agentHome);
    }

    /** Wires this manager in as the registry's rehydrator so resumed chats restore their workspace. */
    @PostConstruct
    void registerRehydrator() {
        workspaceRegistry.setRehydrator(this);
        warnOnSandboxEnabled();
    }

    /**
     * Surfaces a clear, loud startup warning when Docker-backed sandboxes are enabled. Sandbox mode
     * executes agent-issued shell commands inside Docker containers; in the recommended deployment
     * (docker-compose.sandbox.yml) it additionally bind-mounts the host Docker socket, which grants
     * the app container control of the host's Docker daemon. This must only ever run on trusted,
     * self-hosted/local machines and is never enabled silently.
     */
    private void warnOnSandboxEnabled() {
        if (!properties.enabled()) {
            log.info("Docker sandbox mode is disabled (agent.sandbox.enabled=false).");
            return;
        }
        boolean dockerSocketPresent = Files.exists(Path.of("/var/run/docker.sock"));
        log.warn("\n*** SANDBOX SECURITY NOTICE ***\n"
                + "Docker sandbox mode is ENABLED (agent.sandbox.enabled=true). Agent-issued shell commands "
                + "run inside Docker containers via '{}'.{}\n"
                + "Only enable this on trusted local/self-hosted machines. To disable, set SANDBOX_ENABLED=false.",
                properties.dockerBin(),
                dockerSocketPresent
                        ? " The host Docker socket (/var/run/docker.sock) is mounted, which grants this process "
                          + "control of the host Docker daemon — this is powerful and effectively root-equivalent."
                        : "");
    }

    /** Creates and starts a new sandbox container, returning its metadata. */
    public SandboxInfo create(String imageOverride) {
        return createSandbox(imageOverride);
    }

    /**
     * Creates a fully isolated, containerized workspace for a GitHub project chat. A dedicated Docker
     * container and named volume are provisioned and the repository is cloned <em>inside</em> the
     * container at {@code /workspace/repo} — nothing is written to the host. The agent's file and shell
     * tools all execute inside that container (see {@link WorkspaceContext}), so the repository code and
     * command execution stay isolated from the host.
     *
     * <p>This replaces the previous host-clone behaviour: project chats now fail loudly when the
     * isolated sandbox runtime is unavailable rather than silently falling back to running on the host.
     *
     * @param repoFullName the {@code owner/repo} being cloned; used to give the agent repository context
     */
    public SandboxInfo createGitHubRepoSandbox(
            String imageOverride,
            String cloneUrl,
            String repoFullName,
            String branch,
            String accessToken,
            BugReportCatalogService.StoredBugReport bugReport) {
        if (projectSessions.isEmpty() || projectRuntime.isEmpty() || !projectSessions.get().enabled()) {
            throw new IllegalStateException(
                    "Project chats require an isolated sandbox container, but the sandbox runtime is "
                    + "unavailable or disabled (hugin.sandbox.enabled). There is no host fallback for "
                    + "project chats.");
        }
        RepositoryConfig repository = new RepositoryConfig(cloneUrl, repoFullName, branch, accessToken);
        SandboxSession session = projectSessions.get().createForChat(null, repository);
        try {
            if (bugReport != null) {
                stageBugReport(session, bugReport);
            }
            return registerProjectSandbox(session, repoFullName, imageOverride);
        } catch (RuntimeException e) {
            projectSessions.get().delete(session.sandboxId());
            unregisterProjectSandbox(session.sandboxId());
            throw e;
        }
    }

    /**
     * Registers an isolated project sandbox in this process: a placeholder host workspace (used only
     * for skill discovery and path display — never for repository I/O), the container
     * {@link WorkspaceContext} that routes file/shell tools into the container, and the repository
     * context. Returns the {@link SandboxInfo} surfaced to the API.
     */
    private SandboxInfo registerProjectSandbox(SandboxSession session, String repoFullName, String imageOverride) {
        String id = session.sandboxId();
        Path placeholder = placeholderWorkspace(id);
        extractBundledSkillsToPlaceholder(placeholder);
        workspaceRegistry.register(id, workspaceFactory.create(placeholder));
        if (repoFullName != null && !repoFullName.isBlank()) {
            workspaceRegistry.registerGithubRepo(id, repoFullName);
        }
        workspaceRegistry.registerContainerContext(id,
                WorkspaceContext.container(null, id, session.repositoryPath()));
        projectSandboxes.put(id, new ProjectSandbox(session, repoFullName));
        SandboxInfo info = new SandboxInfo(id, containerNameOf(session), sandboxImage(imageOverride),
                statusLabel(session.status()), session.createdAt(), session.repositoryPath());
        log.info("Created isolated project sandbox {} (repo={}, branch={})",
                id, repoFullName, session.repositoryBranch());
        return info;
    }

    private void stageBugReport(SandboxSession session, BugReportCatalogService.StoredBugReport bugReport) {
        String relativePath = bugReport.relativePath();
        if (relativePath == null || relativePath.isBlank()) {
            relativePath = "bug-reports/imported/" + bugReport.id() + ".txt";
        }
        projectRuntime.orElseThrow().writeFile(session.sandboxId(), relativePath, bugReport.content());
    }

    /**
     * Restores an isolated project sandbox whose in-memory registration was lost (e.g. after a
     * restart) from its persisted {@link SandboxSession}, restarting a stopped container as needed.
     * Used by {@link WorkspaceRegistry} on a cache miss so a resumed chat reconnects to its container.
     */
    @Override
    public synchronized boolean rehydrate(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank() || sandboxes.containsKey(sandboxId)
                || projectSandboxes.containsKey(sandboxId)) {
            return sandboxes.containsKey(sandboxId) || projectSandboxes.containsKey(sandboxId);
        }
        if (projectSessions.isEmpty()) {
            return false;
        }
        Optional<SandboxSession> reconnected = projectSessions.get().reconnect(sandboxId);
        if (reconnected.isEmpty()) {
            return false;
        }
        SandboxSession session = reconnected.get();
        if (session.status() != com.example.agent.sandbox.SandboxStatus.READY) {
            log.warn("Project sandbox {} is not recoverable (status={})", sandboxId, session.status());
            return false;
        }
        registerProjectSandbox(session, repoFullNameFromUrl(session.repositoryUrl()), null);
        log.info("Rehydrated isolated project sandbox {} (repo={})",
                sandboxId, repoFullNameFromUrl(session.repositoryUrl()));
        return true;
    }

    /**
     * Writes Hugin's bundled skill files into the placeholder workspace directory so that
     * {@link com.example.agent.WorkspaceSkills} can discover them for the initial system prompt.
     * Failures are logged but never propagate — a missing skill listing is a degraded experience,
     * not a fatal error.
     */
    private void extractBundledSkillsToPlaceholder(Path placeholder) {
        try {
            BundledSkills.extractTo(placeholder);
        } catch (IOException e) {
            log.warn("Could not extract bundled skills to placeholder workspace {}: {}", placeholder, e.getMessage());
        }
    }

    /** Creates (once) the empty per-sandbox host directory used as the placeholder workspace root. */
    private Path placeholderWorkspace(String id) {
        try {
            Path dir = sandboxesHome.resolve(id).resolve("workspace");
            Files.createDirectories(dir);
            return dir.toRealPath();
        } catch (IOException e) {
            throw new RuntimeException("Could not create placeholder workspace for sandbox " + id, e);
        }
    }

    private void unregisterProjectSandbox(String id) {
        projectSandboxes.remove(id);
        workspaceRegistry.unregister(id);
        workspaceRegistry.unregisterContainerContext(id);
        deleteQuietly(sandboxesHome.resolve(id));
    }

    private String containerNameOf(SandboxSession session) {
        return session.containerName() != null ? session.containerName() : GITHUB_WORKSPACE_PREFIX + session.sandboxId();
    }

    private String sandboxImage(String imageOverride) {
        return imageOverride == null || imageOverride.isBlank() ? "hugin-agent-sandbox" : imageOverride;
    }

    private static String statusLabel(com.example.agent.sandbox.SandboxStatus status) {
        return status == com.example.agent.sandbox.SandboxStatus.READY ? SandboxInfo.RUNNING
                : status == com.example.agent.sandbox.SandboxStatus.STOPPED ? SandboxInfo.STOPPED
                : SandboxInfo.ERROR;
    }

    /** Best-effort extraction of {@code owner/repo} from an https clone URL. */
    static String repoFullNameFromUrl(String cloneUrl) {
        if (cloneUrl == null || cloneUrl.isBlank()) {
            return null;
        }
        String trimmed = cloneUrl.trim();
        int scheme = trimmed.indexOf("://");
        String afterScheme = scheme >= 0 ? trimmed.substring(scheme + 3) : trimmed;
        int slash = afterScheme.indexOf('/');
        String path = slash >= 0 ? afterScheme.substring(slash + 1) : afterScheme;
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        path = path.replaceFirst("^/+", "").replaceFirst("/+$", "");
        return path.isBlank() ? null : path;
    }

    /** A registered isolated project sandbox: its persisted session and the {@code owner/repo} it holds. */
    private record ProjectSandbox(SandboxSession session, String repoFullName) {}

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

    public List<SandboxInfo> list() {
        return sandboxes.values().stream().map(LiveSandbox::info).toList();
    }

    public Optional<SandboxInfo> get(String id) {
        if (id != null && !sandboxes.containsKey(id) && !projectSandboxes.containsKey(id)) {
            rehydrate(id);
        }
        ProjectSandbox project = projectSandboxes.get(id);
        if (project != null) {
            return Optional.of(projectSandboxInfo(project));
        }
        return Optional.ofNullable(sandboxes.get(id)).map(LiveSandbox::info);
    }

    /** Builds the API view of an isolated project sandbox, reflecting the container's live status. */
    private SandboxInfo projectSandboxInfo(ProjectSandbox project) {
        SandboxSession session = project.session();
        String status = statusLabel(session.status());
        if (projectRuntime.isPresent()) {
            try {
                SandboxRuntime.SandboxState state = projectRuntime.get().inspect(session.sandboxId());
                if (state != null) {
                    status = statusLabel(state.status());
                }
            } catch (RuntimeException ignored) {
                // fall back to the last persisted status
            }
        }
        return new SandboxInfo(session.sandboxId(), containerNameOf(session), "hugin-agent-sandbox",
                status, session.createdAt(), session.repositoryPath());
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
        if (id != null && !sandboxes.containsKey(id) && !projectSandboxes.containsKey(id)) {
            rehydrate(id);
        }
        if (projectSandboxes.containsKey(id) && projectRuntime.isPresent()) {
            // Isolated project sandboxes have no host workspace: list the repository tree inside the
            // container instead of walking the (empty) placeholder directory.
            try {
                List<FileEntry> entries = projectRuntime.get().listFiles(id, ".");
                List<FileNode> nodes = new ArrayList<>();
                for (FileEntry entry : entries) {
                    if (entry.directory()) {
                        if (Workspace.IGNORED_DIRECTORIES.contains(entry.name())) {
                            continue;
                        }
                        nodes.add(FileNode.directory(entry.name(), entry.path(), List.of()));
                    } else {
                        nodes.add(FileNode.file(entry.name(), entry.path(), entry.size()));
                    }
                }
                return Optional.of(nodes);
            } catch (RuntimeException e) {
                log.warn("Could not list project sandbox {} files: {}", id, e.getMessage());
                return Optional.of(List.of());
            }
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
    @Override
    public void delete(String id) {
        if (id != null && !sandboxes.containsKey(id) && !projectSandboxes.containsKey(id)) {
            // Rehydrate first so deleting a chat resumed after a restart still removes its container
            // and persisted session, not just the (already absent) in-memory entry.
            rehydrate(id);
        }
        if (projectSandboxes.containsKey(id)) {
            // Isolated project chat: destroy the container + volume and its persisted session.
            projectSessions.ifPresent(svc -> svc.delete(id));
            unregisterProjectSandbox(id);
            log.info("Deleted isolated project sandbox {}", id);
            return;
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
        if (!projectSandboxes.containsKey(sandboxId) && !rehydrate(sandboxId)) {
            return false;
        }
        // Project sandbox: defer to the container's live state.
        return projectRuntime.map(rt -> rt.isActive(sandboxId)).orElse(false);
    }

    @Override
    public ExecResult exec(String sandboxId, String command, Duration timeout) {
        if (sandboxId != null && !sandboxes.containsKey(sandboxId) && !projectSandboxes.containsKey(sandboxId)) {
            rehydrate(sandboxId);
        }
        // Isolated project chats run every command inside their container; no host fallback.
        if (projectSandboxes.containsKey(sandboxId) && projectRuntime.isPresent()) {
            return projectRuntime.get().exec(sandboxId, command, timeout);
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

    @Override
    public FileResult readFile(String sandboxId, String path) {
        ensureProjectRuntime(sandboxId);
        return projectRuntime.get().readFile(sandboxId, path);
    }

    @Override
    public void writeFile(String sandboxId, String path, String content) {
        ensureProjectRuntime(sandboxId);
        projectRuntime.get().writeFile(sandboxId, path, content);
    }

    @Override
    public List<FileEntry> listFiles(String sandboxId, String path) {
        ensureProjectRuntime(sandboxId);
        return projectRuntime.get().listFiles(sandboxId, path);
    }

    @Override
    public void restart(String sandboxId) {
        ensureProjectRuntime(sandboxId);
        projectRuntime.get().restart(sandboxId);
    }

    @Override
    public SandboxState inspect(String sandboxId) {
        ensureProjectRuntime(sandboxId);
        return projectRuntime.get().inspect(sandboxId);
    }

    /** Routes a sandbox-id–keyed file/lifecycle call to the isolated runtime, failing loudly otherwise. */
    private void ensureProjectRuntime(String sandboxId) {
        if (sandboxId != null && !projectSandboxes.containsKey(sandboxId)) {
            rehydrate(sandboxId);
        }
        if (projectRuntime.isEmpty()) {
            throw new IllegalStateException(
                    "No isolated sandbox runtime is available for sandbox " + sandboxId);
        }
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
