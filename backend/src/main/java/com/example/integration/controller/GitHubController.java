package com.example.integration.controller;

import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubConnectRequest;
import com.example.integration.github.GitHubConnectResponse;
import com.example.integration.github.GitHubStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Endpoints for checking and connecting the GitHub App integration. */
@RestController
@RequestMapping("/api/github")
public class GitHubController {

    public record GitHubRepositoryOption(
            String fullName,
            String name,
            String owner,
            boolean privateRepo,
            String defaultBranch,
            String description) {
    }

    public record GitHubBranchOption(String name) {
    }

    private final GitHubAppService github;

    public GitHubController(GitHubAppService github) {
        this.github = github;
    }

    @GetMapping("/status")
    public GitHubStatus status() {
        return github.status();
    }

    @GetMapping("/repositories")
    public List<GitHubRepositoryOption> repositories() throws Exception {
        return github.listRepositories().stream()
                .map(repo -> new GitHubRepositoryOption(
                        repo.fullName(),
                        repo.name(),
                        repo.owner(),
                        repo.privateRepo(),
                        repo.defaultBranch(),
                        repo.description()))
                .toList();
    }

    @GetMapping("/repositories/{owner}/{repo}/branches")
    public List<GitHubBranchOption> branches(@PathVariable String owner, @PathVariable String repo) throws Exception {
        return github.listBranches(owner, repo).stream().map(GitHubBranchOption::new).toList();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String installation_id,
                                         @RequestParam(required = false) String setup_action,
                                         @RequestParam(required = false) String state) {
        return finishInstall(installation_id, setup_action, state);
    }

    @GetMapping("/setup")
    public ResponseEntity<Void> setup(@RequestParam(required = false) String installation_id,
                                      @RequestParam(required = false) String setup_action,
                                      @RequestParam(required = false) String state) {
        return finishInstall(installation_id, setup_action, state);
    }

    @PostMapping("/connect")
    public GitHubConnectResponse connect(@RequestBody(required = false) GitHubConnectRequest request) {
        return github.beginConnect(request == null ? null : request.returnTo());
    }

    @PostMapping("/disconnect")
    public GitHubStatus disconnect() {
        return github.disconnect();
    }

    private ResponseEntity<Void> finishInstall(String installationId, String setupAction, String state) {
        github.refresh();
        String target = buildRedirect(state, installationId, setupAction);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .build();
    }

    private static String buildRedirect(String state, String installationId, String setupAction) {
        String target = normalizeReturnTo(state);
        URI uri = URI.create(target);
        StringBuilder redirect = new StringBuilder();
        if (uri.getScheme() != null) {
            redirect.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        }
        String path = uri.getRawPath();
        redirect.append(path == null || path.isBlank() ? "/" : path);
        StringBuilder query = new StringBuilder(uri.getRawQuery() == null ? "" : uri.getRawQuery());
        appendQueryParam(query, "github", "installed");
        if (installationId != null && !installationId.isBlank()) {
            appendQueryParam(query, "installation_id", installationId);
        }
        if (setupAction != null && !setupAction.isBlank()) {
            appendQueryParam(query, "setup_action", setupAction);
        }
        if (query.length() > 0) {
            redirect.append('?').append(query);
        }
        if (uri.getRawFragment() != null && !uri.getRawFragment().isBlank()) {
            redirect.append('#').append(uri.getRawFragment());
        }
        return redirect.toString();
    }

    private static String normalizeReturnTo(String state) {
        if (state == null || state.isBlank()) {
            return "/?screen=integrations";
        }
        String trimmed = state.trim();
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                return trimmed;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the safe in-app default.
        }
        return "/?screen=integrations";
    }

    private static void appendQueryParam(StringBuilder query, String key, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
