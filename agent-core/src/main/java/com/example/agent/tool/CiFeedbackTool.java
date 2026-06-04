package com.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads recent GitHub Actions runs or logs for the current repository via the {@code gh} CLI.
 */
@Component
public class CiFeedbackTool implements LocalTool {

    private final Workspace workspace;
    private final ObjectMapper objectMapper;

    public CiFeedbackTool(Workspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "ci_feedback";
    }

    @Override
    public String description() {
        return "Inspect recent GitHub Actions runs or fetch failed-job logs for the current repo. "
                + "Use this to close the loop when tests fail and you need the exact failure text.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", List.of("latest", "failed", "logs"),
                                "description", "What to inspect."),
                        "run_id", Map.of(
                                "type", "integer",
                                "description", "GitHub Actions run id for action='logs'."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "How many runs to list.")),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        String action = requiredString(arguments, "action").toLowerCase(Locale.ROOT).trim();
        String repo = resolveRepoSlug(ctx.workspace());
        int limit = Math.max(1, optionalInt(arguments, "limit", 5));
        if (repo.isBlank()) {
            return "ci_feedback is unavailable: could not determine the GitHub repository from origin.";
        }

        switch (action) {
            case "latest" -> {
                return runGhJson(repo, "run", "list", "--limit", String.valueOf(limit),
                        "--json", "databaseId,displayTitle,status,conclusion,headBranch,event,createdAt");
            }
            case "failed" -> {
                return runGhJson(repo, "run", "list", "--status", "failure", "--limit", String.valueOf(limit),
                        "--json", "databaseId,displayTitle,status,conclusion,headBranch,event,createdAt");
            }
            case "logs" -> {
                int runId = optionalInt(arguments, "run_id", -1);
                if (runId <= 0) {
                    return "ci_feedback requires run_id when action='logs'.";
                }
                return runGh(repo, "run", "view", String.valueOf(runId), "--log-failed");
            }
            default -> {
                return "Error: unknown action '" + action + "'.";
            }
        }
    }

    private String runGhJson(String repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("gh");
        command.add("--repo");
        command.add(repo);
        command.addAll(List.of(args));
        return runCommand(command);
    }

    private String runGh(String repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("gh");
        command.add("--repo");
        command.add(repo);
        command.addAll(List.of(args));
        return runCommand(command);
    }

    private String runCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspace.root().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        }
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("gh command timed out");
        }
        if (process.exitValue() != 0) {
            return "gh command failed (exit " + process.exitValue() + "):\n" + output;
        }
        if (output.isBlank()) {
            return "(no output)";
        }
        try {
            JsonNode node = objectMapper.readTree(output);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ignored) {
            return output;
        }
    }

    private String resolveRepoSlug(Workspace ws) {
        try {
            String remote = runCommand(List.of("git", "-C", ws.root().toString(), "remote", "get-url", "origin"));
            return parseRepoSlug(remote.trim());
        } catch (Exception e) {
            return "";
        }
    }

    private static String parseRepoSlug(String remote) {
        if (remote == null || remote.isBlank()) {
            return "";
        }
        String trimmed = remote.trim();
        if (trimmed.startsWith("git@github.com:")) {
            trimmed = trimmed.substring("git@github.com:".length());
        } else if (trimmed.startsWith("https://github.com/")) {
            trimmed = trimmed.substring("https://github.com/".length());
        } else if (trimmed.startsWith("http://github.com/")) {
            trimmed = trimmed.substring("http://github.com/".length());
        }
        trimmed = trimmed.replaceAll("\\.git$", "");
        return trimmed.contains("/") ? trimmed : "";
    }
}
