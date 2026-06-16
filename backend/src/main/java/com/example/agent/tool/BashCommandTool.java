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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell command from the workspace root and returns its combined output.
 *
 * <p>By default commands run through a <em>login</em> shell ({@code -l}) so the user's profile is
 * sourced and PATH-dependent tools (e.g. Homebrew's {@code brew}) resolve. The trade-off is that
 * the profile may define aliases/functions or run side-effecting startup code, and runs in the
 * environment the user's profile sets up. Set {@code agent.tools.login-shell=false} to run a plain
 * non-login shell instead when that is a concern (note: like all built-in tools, {@code run_bash}
 * already grants arbitrary shell access and is gated by {@code agent.tools.enabled}).
 */
@Component
public class BashCommandTool implements LocalTool {

    private final Workspace workspace;
    private final Duration timeout;
    private final int maxChars;
    private final String shell;
    private final boolean loginShell;
    private final Optional<SandboxRuntime> sandboxRuntime;

    @Autowired
    public BashCommandTool(Workspace workspace, LocalToolProperties properties,
                           Optional<SandboxRuntime> sandboxRuntime) {
        this.workspace = workspace;
        this.timeout = properties.bashTimeout();
        this.maxChars = properties.maxOutputChars();
        this.shell = resolveShell(properties.shell());
        this.loginShell = properties.loginShell();
        this.sandboxRuntime = sandboxRuntime;
    }

    /** Convenience constructor for tests / hosts without a sandbox runtime (host execution only). */
    public BashCommandTool(Workspace workspace, LocalToolProperties properties) {
        this(workspace, properties, Optional.empty());
    }

    /**
     * Resolves which shell binary to run. An explicit config value wins; otherwise we use the
     * user's login shell from {@code $SHELL} (so {@code run_bash} behaves like the user's own
     * terminal and finds PATH entries set up in their profile, e.g. Homebrew's {@code brew}),
     * falling back to {@code /bin/sh}.
     */
    private static String resolveShell(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String envShell = System.getenv("SHELL");
        if (envShell != null && !envShell.isBlank()) {
            return envShell;
        }
        return "/bin/sh";
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public String name() {
        return "run_bash";
    }

    @Override
    public String description() {
        return "Run a shell command from the workspace root and return its exit code and combined "
                + "stdout/stderr. The command runs through " + (loginShell ? "a login shell" : "a shell")
                + ", so it sees the same PATH and environment as the user's terminal (tools installed "
                + "via Homebrew such as 'brew' are available). Useful for building, running tests, git, "
                + "or other CLI tasks. Commands are subject to a timeout of " + timeout.toSeconds() + "s.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "Shell command line to execute.")),
                "required", List.of("command"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException, InterruptedException {
        String command = requiredString(arguments, "command");

        // When the request is bound to a sandbox, run the command inside that container instead of
        // on the host, so all shell execution is isolated to the per-session sandbox environment.
        String sandboxId = ctx.sandboxId();
        if (sandboxId != null && !sandboxId.isBlank()
                && sandboxRuntime.isPresent() && sandboxRuntime.get().isActive(sandboxId)) {
            SandboxRuntime.ExecResult result = sandboxRuntime.get().exec(sandboxId, command, timeout);
            String output = clamp(result.output());
            if (result.timedOut()) {
                return "Error: command timed out after " + timeout.toSeconds() + "s.\n"
                        + "Partial output:\n" + output;
            }
            return "exit code: " + result.exitCode() + (output.isBlank() ? " (no output)" : "\n" + output);
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add(shell);
        if (loginShell) {
            // Source the user's profile so PATH (e.g. Homebrew's /opt/homebrew/bin) is set up
            // exactly as in their interactive terminal; without this 'brew' is not found.
            commandLine.add("-l");
        }
        commandLine.add("-c");
        commandLine.add(command);

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.directory(ctx.workspace().root().toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, output));
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return "Error: command timed out after " + timeout.toSeconds() + "s.\n"
                    + "Partial output:\n" + render(output);
        }
        reader.join(2000);

        int exitCode = process.exitValue();
        String rendered = render(output);
        return "exit code: " + exitCode + (rendered.isBlank() ? " (no output)" : "\n" + rendered);
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

    /** Caps sandbox command output to the per-result character limit. */
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
