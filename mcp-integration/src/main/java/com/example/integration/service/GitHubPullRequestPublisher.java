package com.example.integration.service;

import com.example.agent.CloudAgentProperties;
import com.example.agent.PullRequestPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens GitHub pull requests for finalized cloud-agent branches.
 */
@Service
@ConditionalOnProperty("agent.cloud.enabled")
public class GitHubPullRequestPublisher implements PullRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestPublisher.class);
    private static final Pattern HTTPS_PATTERN = Pattern.compile("https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SSH_PATTERN = Pattern.compile("git@github\\.com:([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final CloudAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GitHubPullRequestPublisher(CloudAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Optional<String> publish(String repoUrl, Path workingTree, String branch, String baseBranch,
                                    String title, String body) {
        String token = properties.githubToken();
        if (token == null || token.isBlank()) {
            log.warn("Skipping PR creation: no GitHub token configured");
            return Optional.empty();
        }

        RepoCoordinates coords = parse(repoUrl);
        if (coords == null) {
            log.warn("Skipping PR creation: could not parse repository coordinates from {}", repoUrl);
            return Optional.empty();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + coords.owner() + "/" + coords.repo() + "/pulls"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                            java.util.Map.of(
                                    "title", title,
                                    "head", branch,
                                    "base", baseBranch,
                                    "body", body,
                                    "draft", true))))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("GitHub PR creation failed for {}: HTTP {} {}", repoUrl, response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            String htmlUrl = root.path("html_url").asText("");
            if (!htmlUrl.isBlank()) {
                return Optional.of(htmlUrl);
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("GitHub PR creation failed for {}: {}", repoUrl, e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.warn("GitHub PR creation failed for {}: {}", repoUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private static RepoCoordinates parse(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        Matcher https = HTTPS_PATTERN.matcher(repoUrl.trim());
        if (https.find()) {
            return new RepoCoordinates(https.group(1), stripGit(https.group(2)));
        }
        Matcher ssh = SSH_PATTERN.matcher(repoUrl.trim());
        if (ssh.find()) {
            return new RepoCoordinates(ssh.group(1), stripGit(ssh.group(2)));
        }
        return null;
    }

    private static String stripGit(String repo) {
        return repo == null ? "" : repo.replaceAll("\\.git$", "");
    }

    private record RepoCoordinates(String owner, String repo) {}
}
