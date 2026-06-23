package com.example.agent.tool;

import com.example.agent.sandbox.SandboxRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the built-in git tools end-to-end on a real temporary repository (host execution) and
 * verifies that {@link GitCommandRunner} routes commands into an active {@link SandboxRuntime}
 * sandbox, always scoped to the repository (workspace) root.
 */
class GitToolsTest {

    @TempDir
    Path repo;

    private Workspace workspace;
    private LocalToolProperties properties;
    private GitCommandRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        properties = new LocalToolProperties(true, repo.toString(), Duration.ofSeconds(30), 30_000, List.of());
        workspace = new Workspace(properties);
        runner = new GitCommandRunner(properties);

        // Initialise a real repository so the host execution path has something to operate on.
        runner.run(ctx(), List.of("init", "-q"));
        runner.run(ctx(), List.of("config", "user.email", "test@example.com"));
        runner.run(ctx(), List.of("config", "user.name", "Test"));
    }

    private ToolContext ctx() {
        return new ToolContext(workspace);
    }

    @Test
    void commitStagesAndRecordsChangesFromRepoRoot() throws Exception {
        Files.writeString(repo.resolve("hello.txt"), "hi");

        var status = new GitStatusTool(runner, workspace);
        assertThat(status.execute(Map.of(), ctx())).contains("hello.txt");

        var commit = new GitCommitTool(runner, workspace);
        String result = commit.execute(Map.of("message", "add hello"), ctx());
        assertThat(result).contains("exit code: 0");

        var log = new GitLogTool(runner, workspace);
        assertThat(log.execute(Map.of(), ctx())).contains("add hello");

        // Working tree is clean after committing everything.
        assertThat(status.execute(Map.of(), ctx())).doesNotContain("hello.txt");
    }

    @Test
    void createBranchSwitchesBranch() throws Exception {
        // Need at least one commit before branching is meaningful.
        Files.writeString(repo.resolve("a.txt"), "a");
        new GitCommitTool(runner, workspace).execute(Map.of("message", "first"), ctx());

        var branch = new GitCreateBranchTool(runner, workspace);
        assertThat(branch.execute(Map.of("name", "feature/x"), ctx())).contains("exit code: 0");

        var status = new GitStatusTool(runner, workspace);
        assertThat(status.execute(Map.of(), ctx())).contains("feature/x");
    }

    @Test
    void routesGitCommandIntoActiveSandbox() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        SandboxRuntime sandbox = new SandboxRuntime() {
            @Override
            public boolean isActive(String sandboxId) {
                return "sbx-1".equals(sandboxId);
            }

            @Override
            public ExecResult exec(String sandboxId, String command, Duration timeout) {
                seen.set(command);
                return new ExecResult(0, "## main", false);
            }
        };
        GitCommandRunner sandboxRunner = new GitCommandRunner(properties, Optional.of(sandbox));
        var status = new GitStatusTool(sandboxRunner, workspace);

        String result = status.execute(Map.of(),
                new ToolContext(workspace, "session", null, null, null, "sbx-1"));

        assertThat(seen.get()).isEqualTo("git 'status' '--short' '--branch'");
        assertThat(result).contains("exit code: 0").contains("## main");
    }
}
