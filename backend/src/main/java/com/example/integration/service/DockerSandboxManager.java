package com.example.integration.service;

import com.example.agent.model.FileNode;
import com.example.agent.model.SandboxInfo;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.example.integration.sandbox.SandboxProperties;
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
public class DockerSandboxManager implements SandboxRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxManager.class);

    private final SandboxProperties properties;
    private final WorkspaceRegistry workspaceRegistry;
    private final WorkspaceFactory workspaceFactory;
    private final Path sandboxesHome;

    private final ConcurrentHashMap<String, SandboxInfo> sandboxes = new ConcurrentHashMap<>();

    public DockerSandboxManager(
            SandboxProperties properties,
            WorkspaceRegistry workspaceRegistry,
            WorkspaceFactory workspaceFactory,
            @Value("${agent.home:.}") String agentHome) {
        this.properties = properties;
        this.workspaceRegistry = workspaceRegistry;
        this.workspaceFactory = workspaceFactory;
        this.sandboxesHome = Path.of(agentHome).resolve("sandboxes");
    }

    /** Creates and starts a new sandbox container, returning its metadata. */
    public SandboxInfo create(String imageOverride) {
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
            Path dir = sandboxesHome.resolve(id).resolve("workspace");
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
        if (result.timedOut() || result.exitCode() != 0) {
            deleteQuietly(workspace.getParent());
            throw new RuntimeException("Failed to start sandbox container (image=" + image + "): "
                    + (result.timedOut() ? "docker run timed out" : result.output()));
        }

        workspaceRegistry.register(id, workspaceFactory.create(workspace));
        SandboxInfo info = new SandboxInfo(id, containerName, image, SandboxInfo.RUNNING,
                Instant.now(), workspace.toString());
        sandboxes.put(id, info);
        log.info("Created sandbox {} (container={}, image={}, workspace={})",
                id, containerName, image, workspace);
        return info;
    }

    public List<SandboxInfo> list() {
        return List.copyOf(sandboxes.values());
    }

    public Optional<SandboxInfo> get(String id) {
        return Optional.ofNullable(sandboxes.get(id));
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
        SandboxInfo info = sandboxes.get(id);
        if (info == null) {
            return Optional.empty();
        }
        Path root = Path.of(info.workspace());
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
        SandboxInfo info = sandboxes.remove(id);
        workspaceRegistry.unregister(id);
        if (info == null) {
            return;
        }
        ProcessResult result = runProcess(
                List.of(properties.dockerBin(), "rm", "-f", info.containerName()),
                Duration.ofSeconds(30));
        if (result.exitCode() != 0 && !result.timedOut()) {
            log.warn("Could not remove sandbox container {}: {}", info.containerName(), result.output());
        }
        Path workspace = Path.of(info.workspace());
        deleteQuietly(workspace.getParent());
        log.info("Deleted sandbox {}", id);
    }

    @Override
    public boolean isActive(String sandboxId) {
        return sandboxId != null && sandboxes.containsKey(sandboxId);
    }

    @Override
    public ExecResult exec(String sandboxId, String command, Duration timeout) {
        SandboxInfo info = sandboxes.get(sandboxId);
        if (info == null) {
            throw new IllegalStateException("Unknown sandbox: " + sandboxId);
        }
        List<String> execCmd = List.of(
                properties.dockerBin(), "exec", "-w", info.workspace(),
                info.containerName(), "/bin/sh", "-c", command);
        Duration effective = (timeout != null) ? timeout : properties.execTimeout();
        ProcessResult result = runProcess(execCmd, effective);
        return new ExecResult(result.exitCode(), result.output(), result.timedOut());
    }

    @PreDestroy
    public void shutdown() {
        for (String id : List.copyOf(sandboxes.keySet())) {
            try {
                delete(id);
            } catch (Exception e) {
                log.warn("Error cleaning up sandbox {} on shutdown: {}", id, e.getMessage());
            }
        }
    }

    private record ProcessResult(int exitCode, String output, boolean timedOut) {}

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
