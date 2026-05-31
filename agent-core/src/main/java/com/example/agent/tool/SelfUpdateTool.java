package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code hugin update} from the workspace root to rebuild and reinstall the agent
 * from source, reusing existing credentials. The agent can use this tool to update its
 * own code and restart when the update completes.
 */
@Component
public class SelfUpdateTool implements LocalTool {

    private final Workspace workspace;
    private final Duration timeout;
    private final int maxChars;

    private static final Duration MIN_TIMEOUT = Duration.ofMinutes(10);

    public SelfUpdateTool(Workspace workspace, LocalToolProperties properties) {
        this.workspace = workspace;
        // Maven builds take several minutes; use at least 10 minutes regardless of bash-timeout.
        Duration configured = properties.bashTimeout();
        this.timeout = configured.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : configured;
        this.maxChars = properties.maxOutputChars();
    }

    @Override
    public String name() {
        return "self_update";
    }

    @Override
    public String description() {
        return "Rebuild and reinstall the entire hugin agent from source by running "
                + "'hugin update'. No arguments needed. The command is subject to a timeout "
                + "of " + timeout.toSeconds() + "s.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Collections.emptyMap(),
                "required", Collections.emptyList());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException, InterruptedException {
        // Run through bash so PATH is resolved even when the JVM was started with a minimal
        // environment (e.g. macOS LaunchAgent). Prepend common Homebrew / user-local bins.
        String currentPath = System.getenv("PATH") != null ? System.getenv("PATH") : "";
        String extendedPath = "/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:" + currentPath;

        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-c", "hugin update");
        builder.directory(ctx.workspace().root().toFile());
        builder.environment().put("PATH", extendedPath);
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
            return "Error: 'hugin update' timed out after " + timeout.toSeconds() + "s.\n"
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

    private String render(StringBuilder output) {
        if (output.length() >= maxChars) {
            return output + "\n... [output truncated at " + maxChars + " characters]";
        }
        return output.toString();
    }
}
