package com.example.integration.service;

import com.example.agent.sandbox.FileEntry;
import com.example.agent.sandbox.FileResult;
import com.example.agent.sandbox.RepositoryConfig;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.sandbox.SandboxSession;
import com.example.agent.sandbox.SandboxStatus;
import com.example.integration.sandbox.ProjectSandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Docker-CLI implementation of {@link SandboxRuntime} for fully isolated project-chat sandboxes.
 *
 * <p>Every project chat receives its own container ({@code hugin-agent-<id>}) and named volume
 * ({@code hugin-agent-<id>-workspace}) mounted at {@code /workspace}. The repository is cloned
 * <em>inside</em> the container at {@code /workspace/repo}; nothing is written to the host. All tool
 * execution — bash, git, and file read/write/list — runs through {@code docker exec}, so the host
 * filesystem is never touched for a project chat.
 *
 * <p>The container and volume names are derived deterministically from the sandbox id, so this class
 * is effectively stateless: after a restart it can reconnect to (or clean up) any sandbox given only
 * its id, without consulting an in-memory registry. The {@code SandboxSession} persistence handled by
 * {@link SandboxSessionService} carries the surrounding metadata (repository, timestamps, status).
 *
 * <p>Per the deliberate isolation requirement there is no host-execution fallback here: if the Docker
 * CLI is unavailable, operations fail loudly rather than silently running on the host.
 */
@Service
public class DockerSandboxRuntime implements SandboxRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxRuntime.class);
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private final ProjectSandboxProperties properties;

    public DockerSandboxRuntime(ProjectSandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public SandboxSession create(String chatSessionId, RepositoryConfig repository) {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                    "Project sandboxes are disabled. Set hugin.sandbox.enabled=true to enable them.");
        }
        if (repository == null || repository.cloneUrl() == null || repository.cloneUrl().isBlank()) {
            throw new IllegalArgumentException("A repository clone URL is required to create a sandbox.");
        }
        UUID id = UUID.randomUUID();
        String sandboxId = id.toString();
        String containerName = properties.containerName(sandboxId);
        String volumeName = properties.volumeName(sandboxId);
        String repoPath = properties.repositoryPath();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(properties.idleTimeoutHours()));

        // Volume first, so the container has its persistent workspace mount.
        ProcessResult volume = run(List.of(properties.dockerBin(), "volume", "create", volumeName),
                properties.startTimeout(), null);
        requireDockerAvailable(volume, "create the sandbox volume");
        if (!volume.ok()) {
            throw new RuntimeException("Failed to create sandbox volume " + volumeName + ": " + volume.output());
        }

        List<String> runCmd = new ArrayList<>(List.of(
                properties.dockerBin(), "run", "-d",
                "--name", containerName,
                "--memory=" + properties.memory(),
                "--cpus=" + properties.cpus(),
                "--pids-limit=" + properties.pidsLimit(),
                "--security-opt=no-new-privileges",
                "--cap-drop=ALL",
                "-v", volumeName + ":" + properties.workspacePath()));
        if (!properties.network().isBlank()) {
            runCmd.add("--network");
            runCmd.add(properties.network());
        }
        runCmd.add(properties.image());
        // Keep the container alive for the lifetime of the chat so we can exec into it.
        runCmd.addAll(List.of("sleep", "infinity"));

        ProcessResult started = run(runCmd, properties.startTimeout(), null);
        requireDockerAvailable(started, "start the sandbox container");
        if (!started.ok()) {
            destroyQuietly(sandboxId);
            throw new RuntimeException("Failed to start sandbox container " + containerName + ": "
                    + (started.timedOut() ? "docker run timed out" : started.output()));
        }
        String containerId = started.output().strip();

        try {
            cloneRepositoryInContainer(containerName, repoPath, repository);
        } catch (RuntimeException e) {
            destroyQuietly(sandboxId);
            throw e;
        }

        log.info("Created isolated sandbox {} (container={}, volume={}, repo={}, branch={})",
                sandboxId, containerName, volumeName, repository.repoFullName(), repository.branch());
        return new SandboxSession(
                id,
                chatSessionId == null || chatSessionId.isBlank() ? null : UUID.fromString(chatSessionId),
                containerId,
                containerName,
                volumeName,
                repository.cloneUrl(),
                repository.branch(),
                repoPath,
                SandboxStatus.READY,
                now,
                now,
                expiresAt);
    }

    private void cloneRepositoryInContainer(String containerName, String repoPath, RepositoryConfig repository) {
        // Ensure a clean target, then clone directly into /workspace/repo inside the container.
        List<String> rm = List.of(properties.dockerBin(), "exec", containerName,
                "bash", "-lc", "rm -rf " + shellQuote(repoPath));
        run(rm, properties.startTimeout(), null);

        StringBuilder clone = new StringBuilder("git clone --single-branch ");
        if (repository.branch() != null && !repository.branch().isBlank()) {
            clone.append("--branch ").append(shellQuote(repository.branch())).append(' ');
        }
        clone.append(shellQuote(repository.cloneUrl())).append(' ').append(shellQuote(repoPath));

        List<String> cloneCmd = new ArrayList<>(List.of(properties.dockerBin(), "exec"));
        // Authenticate the clone with a transient credential helper, mirroring the host clone path:
        // the token is passed as an exec env var rather than baked into the stored URL.
        if (repository.accessToken() != null && !repository.accessToken().isBlank()) {
            cloneCmd.add("-e");
            cloneCmd.add("GIT_TERMINAL_PROMPT=0");
            cloneCmd.add("-e");
            cloneCmd.add("GIT_CONFIG_COUNT=1");
            cloneCmd.add("-e");
            cloneCmd.add("GIT_CONFIG_KEY_0=credential.helper");
            cloneCmd.add("-e");
            cloneCmd.add("GIT_CONFIG_VALUE_0=!f() { echo username=x-access-token; echo password=$GITHUB_TOKEN; }; f");
            cloneCmd.add("-e");
            cloneCmd.add("GITHUB_TOKEN=" + repository.accessToken());
        }
        cloneCmd.add(containerName);
        cloneCmd.addAll(List.of("bash", "-lc", clone.toString()));

        ProcessResult result = run(cloneCmd, properties.cloneTimeout(), null);
        if (!result.ok()) {
            throw new RuntimeException("Failed to clone repository into sandbox container: "
                    + (result.timedOut() ? "git clone timed out" : result.output()));
        }

        persistGitCredentials(containerName, repository.accessToken());
    }

    /**
     * Refreshes the persisted git credentials of an already-running sandbox with a freshly minted
     * token. Called when a chat reconnects to its sandbox: GitHub App installation tokens expire after
     * roughly an hour, so the token captured at clone time is typically stale by the time a resumed
     * chat tries to push. Re-running the credential helper setup with a current token keeps
     * {@code git_push}/{@code fetch}/{@code pull} working across reconnects. A null/blank token is a
     * no-op (nothing to refresh).
     */
    public void refreshCredentials(String sandboxId, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        persistGitCredentials(properties.containerName(sandboxId), accessToken);
    }

    /**
     * Persists an access token as a git credential helper <em>inside</em> the container so subsequent
     * git operations (push/fetch/pull) authenticate without a prompt.
     *
     * <p>The clone only sees the token through transient {@code docker exec} env vars; once it returns,
     * nothing remembers the credential. Without this step a later {@code git_push} fails with
     * {@code fatal: could not read Username for 'https://github.com': No such device or address},
     * because {@link #exec} runs commands with no token and {@code GIT_TERMINAL_PROMPT} disabled.
     *
     * <p>The token is base64-encoded on the host (avoiding any shell-quoting hazards) and decoded into
     * a {@code 0600} file under {@code $HOME} — outside the {@code /workspace/repo} working tree, so it
     * is never committed or pushed. A global credential helper reads {@code username=x-access-token}
     * and the password from that file. Re-running it simply overwrites the token file, so it is also
     * the refresh path (see {@link #refreshCredentials}).
     */
    private void persistGitCredentials(String containerName, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String tokenB64 = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        String tokenFile = "$HOME/.config/hugin/github-token";
        String helper = "!f() { echo username=x-access-token; echo password=$(cat " + tokenFile + "); }; f";
        String script = "umask 077"
                + " && mkdir -p \"$HOME/.config/hugin\""
                + " && printf %s " + shellQuote(tokenB64) + " | base64 -d > \"" + tokenFile + "\""
                + " && git config --global credential.helper " + shellQuote(helper);

        List<String> cmd = List.of(properties.dockerBin(), "exec", containerName, "bash", "-lc", script);
        ProcessResult result = run(cmd, properties.startTimeout(), null);
        if (!result.ok()) {
            throw new RuntimeException("Failed to configure git credentials in sandbox container: "
                    + (result.timedOut() ? "timed out" : result.output()));
        }
    }

    @Override
    public boolean isActive(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            return false;
        }
        ProcessResult result = run(List.of(properties.dockerBin(), "inspect",
                "-f", "{{.State.Running}}", properties.containerName(sandboxId)),
                Duration.ofSeconds(20), null);
        return result.ok() && "true".equals(result.output().strip());
    }

    @Override
    public SandboxRuntime.ExecResult exec(String sandboxId, String command, Duration timeout) {
        String containerName = properties.containerName(sandboxId);
        Duration effective = timeout != null ? timeout : properties.execTimeout();
        List<String> execCmd = List.of(
                properties.dockerBin(), "exec",
                "-w", properties.repositoryPath(),
                containerName, "bash", "-lc", command);
        ProcessResult result = run(execCmd, effective, null);
        return new SandboxRuntime.ExecResult(result.exitCode(), result.output(), result.timedOut());
    }

    @Override
    public FileResult readFile(String sandboxId, String path) {
        String target = resolveInRepo(path);
        // base64-encode in the container so arbitrary bytes survive transport intact.
        String cmd = "if [ -f " + shellQuote(target) + " ]; then base64 " + shellQuote(target)
                + "; else echo __HUGIN_MISSING__; fi";
        ProcessResult result = run(List.of(properties.dockerBin(), "exec",
                properties.containerName(sandboxId), "bash", "-lc", cmd), properties.execTimeout(), null);
        if (!result.ok()) {
            throw new RuntimeException("Failed to read file " + path + " in sandbox: " + result.output());
        }
        String out = result.output().strip();
        if (out.equals("__HUGIN_MISSING__") || out.isEmpty()) {
            // An empty file legitimately produces no base64 output; distinguish via the marker only.
            if (out.equals("__HUGIN_MISSING__")) {
                return FileResult.missing(path);
            }
            return FileResult.of(path, "", false);
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(out.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Could not decode file content from sandbox for " + path, e);
        }
        boolean truncated = decoded.length > MAX_FILE_BYTES;
        byte[] slice = truncated ? java.util.Arrays.copyOf(decoded, MAX_FILE_BYTES) : decoded;
        return FileResult.of(path, new String(slice, StandardCharsets.UTF_8), truncated);
    }

    @Override
    public void writeFile(String sandboxId, String path, String content) {
        String target = resolveInRepo(path);
        String b64 = Base64.getEncoder().encodeToString(
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        String parent = target.contains("/") ? target.substring(0, target.lastIndexOf('/')) : ".";
        // mkdir -p the parent, then decode the base64 payload into the file.
        String cmd = "mkdir -p " + shellQuote(parent) + " && printf %s " + shellQuote(b64)
                + " | base64 -d > " + shellQuote(target);
        ProcessResult result = run(List.of(properties.dockerBin(), "exec",
                properties.containerName(sandboxId), "bash", "-lc", cmd), properties.execTimeout(), null);
        if (!result.ok()) {
            throw new RuntimeException("Failed to write file " + path + " in sandbox: " + result.output());
        }
    }

    @Override
    public List<FileEntry> listFiles(String sandboxId, String path) {
        String dir = resolveInRepo(path == null || path.isBlank() ? "." : path);
        String cmd = "cd " + shellQuote(dir) + " && ls -1Ap";
        ProcessResult result = run(List.of(properties.dockerBin(), "exec",
                properties.containerName(sandboxId), "bash", "-lc", cmd), properties.execTimeout(), null);
        if (!result.ok()) {
            throw new RuntimeException("Failed to list directory " + path + " in sandbox: " + result.output());
        }
        List<FileEntry> entries = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String name = line.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (name.endsWith("/")) {
                String bare = name.substring(0, name.length() - 1);
                entries.add(FileEntry.directory(bare, bare));
            } else {
                entries.add(FileEntry.file(name, name, 0L));
            }
        }
        return entries;
    }

    @Override
    public void restart(String sandboxId) {
        ProcessResult result = run(List.of(properties.dockerBin(), "start",
                properties.containerName(sandboxId)), properties.startTimeout(), null);
        if (!result.ok()) {
            throw new RuntimeException("Failed to restart sandbox container "
                    + properties.containerName(sandboxId) + ": " + result.output());
        }
        log.info("Restarted sandbox container {}", properties.containerName(sandboxId));
    }

    @Override
    public void delete(String sandboxId) {
        destroyQuietly(sandboxId);
        log.info("Destroyed sandbox {} (container + volume)", sandboxId);
    }

    @Override
    public SandboxState inspect(String sandboxId) {
        ProcessResult result = run(List.of(properties.dockerBin(), "inspect",
                "-f", "{{.Id}} {{.State.Running}}", properties.containerName(sandboxId)),
                Duration.ofSeconds(20), null);
        if (!result.ok()) {
            return new SandboxState(sandboxId, null, SandboxStatus.DESTROYED, false);
        }
        String[] parts = result.output().strip().split("\\s+");
        String containerId = parts.length > 0 ? parts[0] : null;
        boolean running = parts.length > 1 && "true".equals(parts[1]);
        return new SandboxState(sandboxId, containerId,
                running ? SandboxStatus.READY : SandboxStatus.STOPPED, running);
    }

    /** Best-effort removal of a sandbox's container and volume; never throws. */
    private void destroyQuietly(String sandboxId) {
        run(List.of(properties.dockerBin(), "rm", "-f", properties.containerName(sandboxId)),
                Duration.ofSeconds(30), null);
        run(List.of(properties.dockerBin(), "volume", "rm", "-f", properties.volumeName(sandboxId)),
                Duration.ofSeconds(30), null);
    }

    /** Resolves a workspace-relative path to its absolute path inside the container, blocking escapes. */
    private String resolveInRepo(String path) {
        String repo = properties.repositoryPath();
        if (path == null || path.isBlank() || path.equals(".")) {
            return repo;
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("Path '" + path + "' escapes the repository workspace.");
        }
        String clean = path.startsWith("/") ? path.substring(1) : path;
        return repo + "/" + clean;
    }

    private void requireDockerAvailable(ProcessResult result, String action) {
        if (result.dockerMissing()) {
            throw new IllegalStateException("Cannot " + action
                    + ": the Docker CLI is unavailable. Project chats require an isolated container; "
                    + "there is no host fallback.");
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private record ProcessResult(int exitCode, String output, boolean timedOut, boolean dockerMissing) {
        boolean ok() {
            return !timedOut && !dockerMissing && exitCode == 0;
        }
    }

    /** Runs a host process (the docker CLI), capturing combined stdout/stderr. */
    private ProcessResult run(List<String> command, Duration timeout, Map<String, String> ignored) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            boolean missing = command.get(0).equals(properties.dockerBin());
            return new ProcessResult(-1, "Could not run '" + command.get(0) + "': " + e.getMessage(), false, missing);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignoredIo) {
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
                return new ProcessResult(-1, output.toString().strip(), true, false);
            }
            reader.join(2000);
            return new ProcessResult(process.exitValue(), output.toString().strip(), false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessResult(-1, output.toString().strip(), false, false);
        }
    }
}
