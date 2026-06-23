package com.example.agent.tool;

import com.example.agent.sandbox.SandboxRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} subcommands for the built-in git tools, always confined to the repository root.
 *
 * <p>This is the single execution path shared by every git tool so the guard rails live in one
 * place: the command's working directory is the workspace root resolved from the {@link ToolContext}
 * — which, for a GitHub-repo sandbox, is the cloned repository's root — and git is invoked directly
 * (never through a user-controlled path), so the tools cannot operate on a checkout outside the
 * repository folder.
 *
 * <p>When the request is bound to an active {@link SandboxRuntime} sandbox the git command runs
 * inside that container (mirroring {@code run_bash}); otherwise it runs on the host from the
 * workspace root.
 */
@Component
public class GitCommandRunner {

    private final Duration timeout;
    private final int maxChars;
    private final Optional<SandboxRuntime> sandboxRuntime;

    @Autowired
    public GitCommandRunner(LocalToolProperties properties, Optional<SandboxRuntime> sandboxRuntime) {
        this.timeout = properties.bashTimeout();
        this.maxChars = properties.maxOutputChars();
        this.sandboxRuntime = sandboxRuntime;
    }

    /** Convenience constructor for tests / hosts without a sandbox runtime (host execution only). */
    public GitCommandRunner(LocalToolProperties properties) {
        this(properties, Optional.empty());
    }

    /** Combined output and exit status of a git invocation. */
    public record Result(int exitCode, String output, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * Runs {@code git <args...>} from the repository root described by {@code ctx}.
     *
     * @param ctx  carries the workspace (repository root) and optional sandbox binding
     * @param args git arguments, e.g. {@code List.of("commit", "-m", message)}
     */
    public Result run(ToolContext ctx, List<String> args) throws IOException, InterruptedException {
        // Inside a sandbox, route the command into the container exactly like run_bash so the git
        // tools share the same isolated, repository-rooted execution environment.
        String sandboxId = ctx.sandboxId();
        if (sandboxId != null && !sandboxId.isBlank()
                && sandboxRuntime.isPresent() && sandboxRuntime.get().isActive(sandboxId)) {
            String command = "git " + joinForShell(args);
            SandboxRuntime.ExecResult result = sandboxRuntime.get().exec(sandboxId, command, timeout);
            return new Result(result.exitCode(), clamp(result.output()), result.timedOut());
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add("git");
        commandLine.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        // Guard rail: git always runs from the repository (workspace) root.
        builder.directory(ctx.workspace().root().toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, output));
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return new Result(-1, render(output), true);
        }
        reader.join(2000);
        return new Result(process.exitValue(), render(output), false);
    }

    private void drain(Process process, StringBuilder output) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            int ch;
            while ((ch = in.read()) != -1) {
                if (output.length() < maxChars) {
                    output.append((char) ch);
                }
            }
        } catch (IOException ignored) {
            // process output stream closed/interrupted — keep what we have
        }
    }

    /** Single-quotes each argument so it is passed verbatim to the sandbox shell. */
    private static String joinForShell(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('\'').append(arg.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
    }

    private String clamp(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() > maxChars) {
            return output.substring(0, maxChars) + "\n... [output truncated at " + maxChars + " characters]";
        }
        return output;
    }

    private String render(StringBuilder output) {
        if (output.length() >= maxChars) {
            return output + "\n... [output truncated at " + maxChars + " characters]";
        }
        return output.toString();
    }
}
