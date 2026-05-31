package com.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Adds a cron job to the current user's crontab by piping a new entry through
 * {@code crontab -}. After the crontab is updated it attempts a service refresh
 * so the job takes effect immediately (launchctl on macOS, systemctl on Linux).
 */
@Component
public class AddCronJobTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(AddCronJobTool.class);

    private final Duration timeout;
    private final int maxChars;

    public AddCronJobTool(LocalToolProperties properties) {
        this.timeout = properties.bashTimeout();
        this.maxChars = properties.maxOutputChars();
    }

    @Override
    public String name() {
        return "add_cron_job";
    }

    @Override
    public String description() {
        return "Add a cron job to the user's crontab. Accepts a standard 5-field cron schedule "
                + "expression and an absolute path to a script. Optionally accepts a comment label. "
                + "The tool appends the entry to the existing crontab and tries to refresh the cron "
                + "daemon so the job takes effect immediately. "
                + "Subject to a timeout of " + timeout.toSeconds() + "s.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "schedule", Map.of(
                                "type", "string",
                                "description", "Standard 5-field cron schedule expression "
                                        + "(e.g. '0 5 * * *' for daily at 5 AM)."),
                        "script_path", Map.of(
                                "type", "string",
                                "description", "Absolute path to the script the cron job should run."),
                        "comment", Map.of(
                                "type", "string",
                                "description", "Optional label / comment prepended to the cron entry "
                                        + "for identification (e.g. 'Backup database').")),
                "required", List.of("schedule", "script_path"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        String schedule = requiredString(arguments, "schedule");
        String scriptPath = requiredString(arguments, "script_path");
        String comment = optionalString(arguments, "comment", "");

        StringBuilder output = new StringBuilder();

        // 1. Read existing crontab (stderr goes to stdout so we capture "no crontab" messages).
        String existing = runCrontabList();
        output.append("Existing crontab:\n").append(existing).append("\n");

        // 2. Build the new crontab content.
        StringBuilder newCrontab = new StringBuilder();
        // If there was a real crontab (not just the "no crontab" message), keep it.
        if (!existing.isBlank() && !existing.contains("no crontab for")) {
            newCrontab.append(existing);
            if (!existing.endsWith("\n")) {
                newCrontab.append('\n');
            }
        }

        if (!comment.isBlank()) {
            newCrontab.append("# ").append(comment).append('\n');
        }
        newCrontab.append(schedule).append(' ').append(scriptPath).append('\n');

        String crontabContent = newCrontab.toString();

        // 3. Install via crontab -.
        String installResult = runCrontabInstall(crontabContent);
        output.append("Install result: ").append(installResult).append("\n");

        // 4. Verify the entry was added.
        String verify = runCrontabList();
        if (verify.contains(schedule) && verify.contains(scriptPath)) {
            output.append("Cron job added successfully.\n");
        } else {
            output.append("Warning: new entry not found in crontab after install.\n");
        }

        // 5. Try to refresh the cron daemon so it picks up the change immediately.
        String refreshResult = refreshCronDaemon();
        if (!refreshResult.isBlank()) {
            output.append("Cron refresh: ").append(refreshResult).append("\n");
        }

        return render(output);
    }

    /** Reads the current crontab via {@code crontab -l}. */
    private String runCrontabList() throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("crontab", "-l");
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, out));
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return "";
        }
        reader.join(2000);

        return out.toString().trim();
    }

    /** Installs a crontab by piping content into {@code crontab -}. */
    private String runCrontabInstall(String content) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("crontab", "-");
        builder.redirectErrorStream(true);
        Process process = builder.start();

        // Write the content to stdin.
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
        }

        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, out));
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return "timed out";
        }
        reader.join(2000);

        int exitCode = process.exitValue();
        String output = out.toString().trim();
        if (exitCode != 0) {
            return "exit code " + exitCode + (output.isBlank() ? "" : ": " + output);
        }
        return output.isBlank() ? "OK" : output;
    }

    /** Attempts to signal the cron daemon to reload its spool. */
    private String refreshCronDaemon() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac") || os.contains("darwin")) {
                return runRefreshCommand(
                        "launchctl", "kickstart", "-k", "system/com.apple.cron");
            } else if (os.contains("linux")) {
                // systemd is the most common; fall back to SysV init.
                String result = runRefreshCommand("systemctl", "restart", "cron");
                if (result.contains("not found") || result.contains("Failed")) {
                    result = runRefreshCommand("service", "cron", "restart");
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to refresh cron daemon", e);
            return "refresh failed: " + e.getMessage();
        }
        return "unsupported OS for cron refresh: " + os;
    }

    private String runRefreshCommand(String... cmd) {
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> drain(process, out));
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return "timed out";
            }
            reader.join(2000);

            int exitCode = process.exitValue();
            String output = out.toString().trim();
            if (exitCode == 0) {
                return output.isBlank() ? "OK" : output;
            }
            return "exit code " + exitCode + (output.isBlank() ? "" : ": " + output);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
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
