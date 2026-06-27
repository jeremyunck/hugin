package com.example.integration.service;

import com.example.agent.sandbox.FileEntry;
import com.example.agent.sandbox.FileResult;
import com.example.agent.sandbox.RepositoryConfig;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.sandbox.SandboxSession;
import com.example.agent.sandbox.SandboxStatus;
import com.example.integration.sandbox.ProjectSandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test for {@link DockerSandboxRuntime}, driven through a fake {@code docker} CLI that
 * emulates the volume/run/exec/inspect/start/rm subset against a host directory (translating the
 * container's {@code /workspace} mount to that directory). This exercises the real runtime code —
 * cloning the repository "inside the container", running commands, and reading/writing/listing files
 * via {@code docker exec} — deterministically, without a real Docker daemon or network access.
 */
class DockerSandboxRuntimeTest {

    @TempDir
    Path tmp;

    private DockerSandboxRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(commandExists("bash"), "bash is required for the fake docker CLI");
        assumeTrue(commandExists("git"), "git is required to clone the source repository");
        Path fakeDocker = writeFakeDocker(tmp.resolve("docker-root"));
        ProjectSandboxProperties properties = new ProjectSandboxProperties(
                true, "test-image", 72, fakeDocker.toString(),
                "4g", "2", 512, "", "bouw-agent-", "/workspace", "repo",
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(60));
        runtime = new DockerSandboxRuntime(properties);
    }

    @Test
    void createClonesRepositoryThenExecAndFileOpsRunInContainer() throws Exception {
        Path source = initSourceRepo(tmp.resolve("source"));
        RepositoryConfig repo = new RepositoryConfig(source.toUri().toString(), "octo/source", "main", null);

        SandboxSession session = runtime.create(java.util.UUID.randomUUID().toString(), repo);
        String id = session.sandboxId();
        assertThat(session.status()).isEqualTo(SandboxStatus.READY);
        assertThat(session.containerName()).isEqualTo("bouw-agent-" + id);
        assertThat(session.dockerVolumeName()).isEqualTo("bouw-agent-" + id + "-workspace");
        assertThat(runtime.isActive(id)).isTrue();

        // The repository was cloned INSIDE the container at /workspace/repo.
        SandboxRuntime.ExecResult ls = runtime.exec(id, "cat README.md", Duration.ofSeconds(10));
        assertThat(ls.exitCode()).isZero();
        assertThat(ls.output()).contains("hello world");

        // Write a new file through the runtime, then read it back — all inside the container.
        runtime.writeFile(id, "notes/todo.txt", "line one\nline two\n");
        FileResult read = runtime.readFile(id, "notes/todo.txt");
        assertThat(read.exists()).isTrue();
        assertThat(read.content()).isEqualTo("line one\nline two\n");

        // Listing the workspace shows the cloned file and the new directory.
        List<FileEntry> entries = runtime.listFiles(id, ".");
        assertThat(entries).extracting(FileEntry::name).contains("README.md", "notes");

        FileResult missing = runtime.readFile(id, "does-not-exist.txt");
        assertThat(missing.exists()).isFalse();

        // Restart and inspect reflect a live container; delete tears it down.
        runtime.restart(id);
        SandboxRuntime.SandboxState state = runtime.inspect(id);
        assertThat(state.running()).isTrue();
        assertThat(state.status()).isEqualTo(SandboxStatus.READY);

        runtime.delete(id);
        assertThat(runtime.isActive(id)).isFalse();
    }

    @Test
    void createPersistsGitCredentialsSoLaterGitOpsAuthenticate() throws Exception {
        Path source = initSourceRepo(tmp.resolve("source"));
        // A token supplied at clone time must be persisted as an in-container credential helper so a
        // subsequent git_push (run through exec, with GIT_TERMINAL_PROMPT=0) can authenticate instead
        // of failing with "could not read Username for 'https://github.com'".
        RepositoryConfig repo = new RepositoryConfig(
                source.toUri().toString(), "octo/source", "main", "secret-token-123");

        SandboxSession session = runtime.create(java.util.UUID.randomUUID().toString(), repo);
        String id = session.sandboxId();

        // The token is stored in a 0600 file outside the repository working tree.
        SandboxRuntime.ExecResult token = runtime.exec(
                id, "cat \"$HOME/.config/bouw/github-token\"", Duration.ofSeconds(10));
        assertThat(token.exitCode()).isZero();
        assertThat(token.output().strip()).isEqualTo("secret-token-123");

        // A global credential helper is configured and resolves github.com to the token, so git can
        // authenticate without prompting.
        SandboxRuntime.ExecResult filled = runtime.exec(id,
                "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill",
                Duration.ofSeconds(10));
        assertThat(filled.exitCode()).isZero();
        assertThat(filled.output()).contains("username=x-access-token");
        assertThat(filled.output()).contains("password=secret-token-123");

        runtime.delete(id);
    }

    @Test
    void refreshCredentialsReplacesTheStoredTokenForReconnect() throws Exception {
        Path source = initSourceRepo(tmp.resolve("source"));
        RepositoryConfig repo = new RepositoryConfig(
                source.toUri().toString(), "octo/source", "main", "clone-time-token");

        SandboxSession session = runtime.create(java.util.UUID.randomUUID().toString(), repo);
        String id = session.sandboxId();

        // Simulate a reconnect minting a fresh installation token after the clone-time one expired.
        runtime.refreshCredentials(id, "refreshed-token");

        SandboxRuntime.ExecResult filled = runtime.exec(id,
                "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill",
                Duration.ofSeconds(10));
        assertThat(filled.exitCode()).isZero();
        assertThat(filled.output()).contains("password=refreshed-token");
        assertThat(filled.output()).doesNotContain("clone-time-token");

        runtime.delete(id);
    }

    @Test
    void refreshCredentialsWithBlankTokenIsANoOp() throws Exception {
        Path source = initSourceRepo(tmp.resolve("source"));
        RepositoryConfig repo = new RepositoryConfig(
                source.toUri().toString(), "octo/source", "main", "clone-time-token");

        SandboxSession session = runtime.create(java.util.UUID.randomUUID().toString(), repo);
        String id = session.sandboxId();

        // A blank token must not clobber the previously persisted credential.
        runtime.refreshCredentials(id, "  ");

        SandboxRuntime.ExecResult token = runtime.exec(
                id, "cat \"$HOME/.config/bouw/github-token\"", Duration.ofSeconds(10));
        assertThat(token.output().strip()).isEqualTo("clone-time-token");

        runtime.delete(id);
    }

    @Test
    void createWithoutTokenDoesNotConfigureCredentialHelper() throws Exception {
        Path source = initSourceRepo(tmp.resolve("source"));
        RepositoryConfig repo = new RepositoryConfig(source.toUri().toString(), "octo/source", "main", null);

        SandboxSession session = runtime.create(java.util.UUID.randomUUID().toString(), repo);
        String id = session.sandboxId();

        // No token at clone time means no persisted credential file or global helper.
        SandboxRuntime.ExecResult tokenFile = runtime.exec(
                id, "test -f \"$HOME/.config/bouw/github-token\"; echo $?", Duration.ofSeconds(10));
        assertThat(tokenFile.output().strip()).isEqualTo("1");

        runtime.delete(id);
    }

    private Path initSourceRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        run(repo, "git", "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "hello world\n");
        run(repo, "git", "add", "README.md");
        run(repo, "git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "init");
        return repo;
    }

    private void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        assertThat(process.waitFor()).isZero();
    }

    private static boolean commandExists(String command) {
        try {
            return new ProcessBuilder(command, "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes a bash script emulating the docker subset DockerSandboxRuntime uses. */
    private Path writeFakeDocker(Path root) throws Exception {
        Files.createDirectories(root);
        String script = """
                #!/usr/bin/env bash
                ROOT='%s'
                cmd="$1"; shift
                case "$cmd" in
                  volume)
                    sub="$1"; shift
                    if [ "$sub" = create ]; then mkdir -p "$ROOT/volumes/$1"; echo "$1"; exit 0; fi
                    if [ "$sub" = rm ]; then shift; rm -rf "$ROOT/volumes/$1" 2>/dev/null; exit 0; fi
                    exit 0;;
                  run)
                    name=""
                    while [ $# -gt 0 ]; do
                      case "$1" in
                        --name) name="$2"; shift 2;;
                        -v) shift 2;;
                        --network) shift 2;;
                        -d|--security-opt=*|--cap-drop=*|--memory=*|--cpus=*|--pids-limit=*) shift;;
                        *) break;;
                      esac
                    done
                    mkdir -p "$ROOT/fs/$name/workspace"
                    echo "fakeid-$name"
                    exit 0;;
                  exec)
                    workdir=""
                    envs=()
                    while [ $# -gt 0 ]; do
                      case "$1" in
                        -w) workdir="$2"; shift 2;;
                        -e) envs+=("$2"); shift 2;;
                        *) break;;
                      esac
                    done
                    name="$1"; shift
                    shift # bash
                    shift # -lc
                    script="$1"
                    base="$ROOT/fs/$name"
                    script="${script//\\/workspace/$base/workspace}"
                    cwd="$base/workspace"
                    if [ -n "$workdir" ]; then cwd="${workdir//\\/workspace/$base/workspace}"; fi
                    mkdir -p "$cwd" 2>/dev/null
                    # Give each fake container its own HOME so `git config --global` and the persisted
                    # credential helper stay isolated (as in a real container) instead of writing to
                    # the host's ~/.gitconfig.
                    home="$base/home"
                    mkdir -p "$home" 2>/dev/null
                    ( cd "$cwd" 2>/dev/null && env HOME="$home" "${envs[@]}" bash -lc "$script" )
                    exit $?;;
                  inspect)
                    fmt=""; name=""
                    while [ $# -gt 0 ]; do
                      case "$1" in
                        -f) fmt="$2"; shift 2;;
                        *) name="$1"; shift;;
                      esac
                    done
                    if [ -d "$ROOT/fs/$name" ]; then
                      case "$fmt" in
                        *Id*) echo "fakeid-$name true";;
                        *) echo "true";;
                      esac
                      exit 0
                    fi
                    exit 1;;
                  start) exit 0;;
                  rm) shift; rm -rf "$ROOT/fs/$1" 2>/dev/null; exit 0;;
                  *) exit 0;;
                esac
                """.formatted(root.toString());
        Path file = tmp.resolve("fake-docker.sh");
        Files.writeString(file, script);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }
}
