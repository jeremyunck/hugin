package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.github.GitHubAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Lists the repositories the GitHub App installation can access. Authenticated as a GitHub App via
 * {@link GitHubAppService} — only advertised to the model while that integration is connected.
 */
@Component
public class GitHubListRepositoriesTool implements LocalTool {

    private final GitHubAppService github;
    private final ObjectMapper objectMapper;

    public GitHubListRepositoriesTool(GitHubAppService github, ObjectMapper objectMapper) {
        this.github = github;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isAvailable() {
        return github.isActive();
    }

    @Override
    public String name() {
        return "github_list_repositories";
    }

    @Override
    public String description() {
        return "List the GitHub repositories the connected GitHub App installation can access. "
                + "Returns each repository's full name (owner/repo), visibility, and description.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        HttpResponse<String> response = github.api("GET", "/installation/repositories?per_page=100", null);
        if (response.statusCode() >= 300) {
            return "Failed to list repositories: HTTP " + response.statusCode() + " " + response.body();
        }
        JsonNode repos = objectMapper.readTree(response.body()).path("repositories");
        if (!repos.isArray() || repos.isEmpty()) {
            return "No repositories are accessible to this GitHub App installation.";
        }
        StringBuilder sb = new StringBuilder("Accessible repositories:\n");
        for (JsonNode repo : repos) {
            sb.append("- ").append(repo.path("full_name").asText());
            sb.append(repo.path("private").asBoolean() ? " (private)" : " (public)");
            String desc = repo.path("description").asText("");
            if (!desc.isBlank()) {
                sb.append(" — ").append(desc);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
