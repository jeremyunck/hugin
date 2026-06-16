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
 * Creates an issue in a repository accessible to the GitHub App installation. Authenticated as a
 * GitHub App via {@link GitHubAppService} — only advertised to the model while that integration is
 * connected.
 */
@Component
public class GitHubCreateIssueTool implements LocalTool {

    private final GitHubAppService github;
    private final ObjectMapper objectMapper;

    public GitHubCreateIssueTool(GitHubAppService github, ObjectMapper objectMapper) {
        this.github = github;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isAvailable() {
        return github.isActive();
    }

    @Override
    public String name() {
        return "github_create_issue";
    }

    @Override
    public String description() {
        return "Open a new issue on a GitHub repository the connected GitHub App can access. "
                + "Provide the repository as owner/repo, a title, and an optional markdown body.";
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
                                "description", "The issue title"),
                        "body", Map.of(
                                "type", "string",
                                "description", "Optional issue body (markdown)")),
                "required", List.of("repository", "title"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String repository = requiredString(arguments, "repository").trim();
        String title = requiredString(arguments, "title");
        String body = optionalString(arguments, "body", "");
        if (!repository.matches("[^/\\s]+/[^/\\s]+")) {
            return "Invalid repository '" + repository + "'. Use owner/repo form, e.g. octocat/hello-world.";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        if (!body.isBlank()) {
            payload.put("body", body);
        }

        HttpResponse<String> response = github.api(
                "POST", "/repos/" + repository + "/issues", objectMapper.writeValueAsString(payload));
        if (response.statusCode() >= 300) {
            return "Failed to create issue: HTTP " + response.statusCode() + " " + response.body();
        }
        JsonNode issue = objectMapper.readTree(response.body());
        return "Created issue #" + issue.path("number").asInt() + ": " + issue.path("html_url").asText();
    }
}
