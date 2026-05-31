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

/** Retrieves the version of the hugin CLI. */
@Component
public class HuginVersionTool implements LocalTool {

    private final Duration timeout;
    private final int maxChars;

    public HuginVersionTool(LocalToolProperties properties) {
        this.timeout = properties.bashTimeout();
        this.maxChars = properties.maxOutputChars();
    }

    @Override
    public String name() {
        return "hugin_version";
    }

    @Override
    public String description() {
        return "Get the version of the hugin CLI tool by executing 'hugin --version'.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("hugin", "--version");
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
            return "Error: hugin --version timed out after " + timeout.toSeconds() + "s.";
        }
        reader.join(2000);

        int exitCode = process.exitValue();
        String rendered = render(output);
        if (exitCode != 0) {
            return "Error: hugin --version exited with code " + exitCode + "\n" + rendered;
        }
        return rendered;
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
