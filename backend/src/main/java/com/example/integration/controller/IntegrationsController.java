package com.example.integration.controller;

import com.example.agent.model.IntegrationStatus;
import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubStatus;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleWorkspaceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports the connection state of the external integrations the agent can use.
 *
 * <p>Integrations are modelled as tools: each entry lists the tool names it provides and whether it
 * is {@code connected}. The agent only advertises an integration's tools to the model while the
 * integration is connected (see {@code LocalTool.isAvailable()}), so this endpoint is the UI-facing
 * mirror of that gate. Per-integration connect/disconnect flows live on their own controllers (e.g.
 * {@code /api/google/reconnect}).
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationsController {

    private static final List<String> GOOGLE_TOOLS = List.of(
            "google_docs_create", "google_docs_read", "google_docs_edit",
            "google_sheets_create", "google_sheets_read", "google_sheets_write", "google_sheets_append",
            "google_gmail_search", "google_gmail_read", "google_gmail_send",
            "google_calendar_create");

    private static final List<String> GITHUB_TOOLS = List.of(
            "github_list_repositories", "github_create_issue");

    private final GoogleWorkspaceClientFactory google;
    private final GitHubAppService github;
    private final String webSearchApiKey;

    public IntegrationsController(GoogleWorkspaceClientFactory google,
                                 GitHubAppService github,
                                 @Value("${OPEN_ROUTER_API_KEY:}") String webSearchApiKey) {
        this.google = google;
        this.github = github;
        this.webSearchApiKey = webSearchApiKey;
    }

    @GetMapping
    public List<IntegrationStatus> list() {
        List<IntegrationStatus> integrations = new ArrayList<>();
        integrations.add(googleStatus());
        integrations.add(githubStatus());
        integrations.add(webSearchStatus());
        return integrations;
    }

    private IntegrationStatus githubStatus() {
        GitHubStatus status = github.status();
        return new IntegrationStatus(
                "github",
                "GitHub",
                "Browse repositories and open issues via a GitHub App",
                status.active(),
                status.reconnectable(),
                status.authMode(),
                GITHUB_TOOLS,
                status.message());
    }

    private IntegrationStatus googleStatus() {
        GoogleWorkspaceStatus status = google.status();
        return new IntegrationStatus(
                "google",
                "Google Workspace",
                "Docs, Sheets, Gmail, and Calendar",
                status.active(),
                status.reconnectable(),
                status.authMode(),
                GOOGLE_TOOLS,
                status.message());
    }

    private IntegrationStatus webSearchStatus() {
        boolean connected = webSearchApiKey != null && !webSearchApiKey.isBlank();
        return new IntegrationStatus(
                "web_search",
                "Web Search",
                "Up-to-date answers from the web",
                connected,
                false,
                connected ? "api-key" : "none",
                List.of("web_search"),
                connected
                        ? "Web search is connected."
                        : "Web search is not configured. Set OPEN_ROUTER_API_KEY to enable it.");
    }
}
