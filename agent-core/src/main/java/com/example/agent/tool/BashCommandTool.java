package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a shell command from the workspace root and returns its combined output. */
@Component
public class BashCommandTool implements LocalTool {

    private final Workspace workspace;
    private final Duration timeout;
    private final int maxChars;

    public BashCommandTool(Workspace workspace, LocalToolProperties properties) {
        this.workspace = workspace;
        this.timeout = properties.bashTimeout();
        this.maxChars = properties.maxOutputChars();
    }

    @Override
    public String name() {
        return "run_bash";
    }

    @Override
    public String description() {
        return "Run a shell command with 'sh -c' from the workspace root and return its exit code "
                + "and combined stdout/stderr. Useful for building, running tests, git, or other CLI "
                + "tasks. Commands are subject to a timeout of " + timeout.toSeconds() + "s.";
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
        String command = requiredString(arguments, "command");

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
        builder.directory(workspace.root().toFile());
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

    private String render(StringBuilder output) {
        if (output.length() >= maxChars) {
            return output + "\n... [output truncated at " + maxChars + " characters]";
        }
        return output.toString();
    }
}
