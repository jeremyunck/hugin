package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.github.GitHubAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates a pull request in a repository accessible to the GitHub App installation. Authenticated
 * as a GitHub App via {@link GitHubAppService} — only advertised to the model while that
 * integration is connected.
 */
@Component
public class GitHubCreatePullRequestTool implements LocalTool {

    private final GitHubAppService github;
    private final ObjectMapper objectMapper;

    public GitHubCreatePullRequestTool(GitHubAppService github, ObjectMapper objectMapper) {
        this.github = github;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isAvailable() {
        return github.isActive();
    }

    @Override
    public String name() {
        return "github_create_pull_request";
    }

    @Override
    public String description() {
        return "Create a pull request on a GitHub repository the connected GitHub App can access. "
                + "Provide the repository as owner/repo, a title, the head branch, the base branch, "
                + "and an optional markdown body.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "repository", Map.of(
                                "type", "string",
                                "description", "Target repository in owner/repo form, e.g. octocat/hello-world"),
                        "title", Map.of(
                                "type", "string",
                                "description", "The pull request title"),
                        "head", Map.of(
                                "type", "string",
                                "description", "The name of the branch where changes are implemented"),
                        "base", Map.of(
                                "type", "string",
                                "description", "The name of the branch you want the changes pulled into"),
                        "body", Map.of(
                                "type", "string",
                                "description", "Optional pull request body (markdown)")),
                "required", List.of("repository", "title", "head", "base"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String repository = requiredString(arguments, "repository").trim();
        String title = requiredString(arguments, "title");
        String head = requiredString(arguments, "head");
        String base = requiredString(arguments, "base");
        String body = optionalString(arguments, "body", "");

        if (!repository.matches("[^/\\s]+/[^/\\s]+")) {
            return "Invalid repository '" + repository + "'. Use owner/repo form, e.g. octocat/hello-world.";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("head", head);
        payload.put("base", base);
        if (!body.isBlank()) {
            payload.put("body", body);
        }

        HttpResponse<String> response = github.api(
                "POST", "/repos/" + repository + "/pulls", objectMapper.writeValueAsString(payload));
        if (response.statusCode() >= 300) {
            return "Failed to create pull request: HTTP " + response.statusCode() + " " + response.body();
        }
        JsonNode pr = objectMapper.readTree(response.body());
        return "Created pull request #" + pr.path("number").asInt() + ": " + pr.path("html_url").asText();
    }
}
