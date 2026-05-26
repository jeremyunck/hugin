package com.example.integration.service;

import com.example.agent.CloudAgentProperties;
import com.example.agent.RepositoryProvisioner;
import com.example.agent.model.ProvisionedRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git-CLI implementation of {@link RepositoryProvisioner}.
 *
 * <p>Shells out to the system {@code git} binary (same ProcessBuilder approach as
 * {@code BashCommandTool}). A {@code GITHUB_TOKEN} credential helper is configured so the token
 * is never written into {@code .git/config}.
 */
@Component
@ConditionalOnProperty("agent.cloud.enabled")
public class GitRepositoryProvisioner implements RepositoryProvisioner {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryProvisioner.class);
    private static final long GIT_TIMEOUT_SECONDS = 300;
    private static final Pattern REPO_NAME_PATTERN = Pattern.compile("[^/]+?(?:\\.git)?$");

    private final CloudAgentProperties properties;

    public GitRepositoryProvisioner(CloudAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProvisionedRepo provision(String repoUrl, Path agentDir, String sourceBranch, String newBranchName) {
        String repoName = extractRepoName(repoUrl);
        Path workingTree = agentDir.resolve(repoName);

        // Clone
        List<String> cloneCmd = new ArrayList<>(List.of("git", "clone"));
        if (sourceBranch != null && !sourceBranch.isBlank()) {
            cloneCmd.addAll(List.of("-b", sourceBranch));
        }
        cloneCmd.addAll(List.of(repoUrl, workingTree.toString()));
        runGit(cloneCmd, agentDir, "clone");

        // Create and check out the working branch
        runGit(List.of("git", "checkout", "-b", newBranchName), workingTree, "checkout -b");

        log.info("Provisioned repo {} at {} on branch {}", repoName, workingTree, newBranchName);
        return new ProvisionedRepo(workingTree, newBranchName, repoName);
    }

    private void runGit(List<String> command, Path workingDir, String label) {
        try {
            Files.createDirectories(workingDir);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            configureAuth(pb);

            log.debug("git {}: {}", label, command);
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
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git " + label + " error: " + e.getMessage(), e);
        }
    }

    private void configureAuth(ProcessBuilder pb) {
        String token = properties.githubToken();
        if (token == null || token.isBlank()) {
            return;
        }
        Map<String, String> env = pb.environment();
        // Use GIT_ASKPASS so the token is never written into .git/config
        String askPassScript = "echo " + token;
        env.put("GIT_ASKPASS", "sh");
        env.put("GIT_ASKPASS_ARGS", "-c");
        // More portable: set the credential via a transient helper
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "credential.helper");
        env.put("GIT_CONFIG_VALUE_0", "!echo password=" + token);
    }

    private static String extractRepoName(String repoUrl) {
        Matcher m = REPO_NAME_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group().replaceAll("\\.git$", "");
        }
        return "repo";
    }
}
