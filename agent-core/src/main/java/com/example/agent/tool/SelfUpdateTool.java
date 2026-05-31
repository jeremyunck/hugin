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

    public SelfUpdateTool(Workspace workspace, LocalToolProperties properties) {
        this.workspace = workspace;
        this.timeout = properties.bashTimeout();
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
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "hugin update");
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
