package com.example.integration.service;

import com.example.agent.PullRequestPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git-CLI + GitHub REST API implementation of {@link PullRequestPublisher}.
 *
 * <p>Uses the system {@code git} binary for commit/push (same ProcessBuilder approach as
 * {@code GitRepositoryProvisioner}) and makes a thin HTTP call to the GitHub REST API
 * to open the pull request. No GitHub SDK dependency — just {@code java.net.http}.
 */
@Component
@ConditionalOnProperty("agent.cloud.enabled")
public class GitHubPullRequestPublisher implements PullRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestPublisher.class);
    private static final long GIT_TIMEOUT_SECONDS = 120;
    private static final Pattern GITHUB_REPO_PATTERN =
            Pattern.compile("github\\.com[/:]([^/]+)/([^/.]+)(?:\\.git)?");

    private final String githubToken;
    private final ObjectMapper objectMapper;

    public GitHubPullRequestPublisher(
            @org.springframework.beans.factory.annotation.Value("${agent.cloud.github-token:}") String githubToken,
            ObjectMapper objectMapper) {
        this.githubToken = githubToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public String publish(Path workingTree, String repoUrl, String branch,
                          String baseBranch, String title) {
        // 1. Detect changes
        String statusOutput = runGitCapture(workingTree, List.of("status", "--porcelain"), "status");
        boolean hasChanges = statusOutput != null && !statusOutput.isBlank();

        if (!hasChanges) {
            log.info("No changes detected in {} — skipping commit/push/PR", workingTree);
            return null;
        }

        // 2. Commit
        runGit(workingTree, List.of("add", "-A"), "add");
        runGit(workingTree, List.of("commit", "-m", title), "commit");

        // 3. Push
        runGit(workingTree, List.of("push", "origin", branch), "push");

        // 4. Open PR via GitHub REST API
        return openPullRequest(repoUrl, branch, baseBranch, title);
    }

    private String openPullRequest(String repoUrl, String head, String base, String title) {
        String repoPath = extractRepoPath(repoUrl);
        if (repoPath == null) {
            log.warn("Could not extract GitHub repo path from URL: {}", repoUrl);
            return null;
        }
        if (githubToken == null || githubToken.isBlank()) {
            log.warn("No GitHub token configured — cannot open PR for {}", repoUrl);
            return null;
        }

        try {
            URL url = URI.create("https://api.github.com/repos/" + repoPath + "/pulls").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + githubToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setDoOutput(true);

            String body = objectMapper.writeValueAsString(new PullRequestBody(title, head, base));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                JsonNode json = objectMapper.readTree(conn.getInputStream());
                String prUrl = json.get("html_url").asText();
                log.info("PR opened: {}", prUrl);
                return prUrl;
            } else {
                String errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("GitHub API returned {} for PR creation: {}", status, errorBody);
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to open PR for {}: {}", repoUrl, e.getMessage());
            return null;
        }
    }

    private void runGit(Path workingDir, List<String> args, String label) {
        runGitCapture(workingDir, args, label);
    }

    private String runGitCapture(Path workingDir, List<String> args, String label) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            configureAuth(pb);

            log.debug("git {}: {}", label, String.join(" ", args));
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("git " + label + " timed out after " + GIT_TIMEOUT_SECONDS + "s");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "git " + label + " failed (exit " + exitCode + "):\n" + output);
            }
            return output.toString().trim();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git " + label + " error: " + e.getMessage(), e);
        }
    }

    private void configureAuth(ProcessBuilder pb) {
        if (githubToken == null || githubToken.isBlank()) {
            return;
        }
        pb.environment().put("GIT_CONFIG_COUNT", "1");
        pb.environment().put("GIT_CONFIG_KEY_0", "credential.helper");
        pb.environment().put("GIT_CONFIG_VALUE_0", "!echo password=" + githubToken);
    }

    private static String extractRepoPath(String repoUrl) {
        Matcher m = GITHUB_REPO_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        return null;
    }

    /** Request body for {@code POST /repos/{owner}/{repo}/pulls}. */
    private record PullRequestBody(String title, String head, String base) {}
}