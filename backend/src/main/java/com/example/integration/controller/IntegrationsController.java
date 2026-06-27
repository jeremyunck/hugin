package com.example.integration.controller;

import com.example.agent.model.IntegrationStatus;
import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubStatus;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleWorkspaceStatus;
import com.example.integration.mcp.McpConnectionService;
import com.example.integration.mcp.McpServerDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
            "google_drive_search", "google_drive_read_file",
            "google_docs_create", "google_docs_read", "google_docs_edit",
            "google_sheets_create", "google_sheets_read", "google_sheets_write", "google_sheets_append",
            "google_gmail_search", "google_gmail_read", "google_gmail_send", "google_gmail_trash",
            "google_calendar_create");

    private static final List<String> GITHUB_TOOLS = List.of(
            "github_list_repositories", "github_create_issue", "github_create_pull_request");

    private final GoogleWorkspaceClientFactory google;
    private final GitHubAppService github;
    private final McpConnectionService mcp;
    private final String webSearchApiKey;

    public IntegrationsController(GoogleWorkspaceClientFactory google,
                                 GitHubAppService github,
                                 McpConnectionService mcp,
                                 @Value("${OPEN_ROUTER_API_KEY:}") String webSearchApiKey) {
        this.google = google;
        this.github = github;
        this.mcp = mcp;
        this.webSearchApiKey = webSearchApiKey;
    }

    @GetMapping
    public List<IntegrationStatus> list(@AuthenticationPrincipal Jwt jwt) {
        List<IntegrationStatus> integrations = new ArrayList<>();
        integrations.add(googleStatus());
        integrations.add(githubStatus());
        integrations.add(webSearchStatus());
        integrations.add(mcpStatus(owner(jwt)));
        return integrations;
    }

    /**
     * Aggregate status for a user's MCP connections. "Connected" means the user has at least one
     * enabled server AND at least one enabled, non-stale tool — i.e. the agent would actually advertise
     * something. The tool list is scoped to this user only; another user's tools are never included.
     */
    private IntegrationStatus mcpStatus(String owner) {
        List<McpServerDto> servers = mcp.listDtos(owner);
        boolean hasEnabledServer = servers.stream().anyMatch(McpServerDto::enabled);
        List<String> enabledTools = new ArrayList<>();
        for (McpServerDto server : servers) {
            if (!server.enabled()) {
                continue;
            }
            server.tools().stream()
                    .filter(tool -> tool.enabled() && !tool.stale())
                    .forEach(tool -> enabledTools.add(tool.bouwToolName()));
        }
        boolean connected = hasEnabledServer && !enabledTools.isEmpty();
        String message;
        if (servers.isEmpty()) {
            message = "No MCP servers connected yet. Add one to expose its tools to Bouw.";
        } else if (connected) {
            message = enabledTools.size() + " MCP tool(s) available across "
                    + servers.size() + " server(s).";
        } else {
            message = "Connected server(s) have no enabled tools yet. Discover and enable tools to use them.";
        }
        return new IntegrationStatus(
                "mcp",
                "MCP Servers",
                "Connect custom Model Context Protocol servers to expand what Bouw can do",
                connected,
                false,
                "mcp",
                enabledTools,
                message);
    }

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "global";
        }
        return jwt.getSubject();
    }

    private IntegrationStatus githubStatus() {
        GitHubStatus status = github.status();
        return new IntegrationStatus(
                "github",
                "GitHub",
                "Browse repositories and open issues or pull requests via a GitHub App",
                status.active(),
                true,
                status.authMode(),
                GITHUB_TOOLS,
                status.message());
    }

    private IntegrationStatus googleStatus() {
        GoogleWorkspaceStatus status = google.status();
        return new IntegrationStatus(
                "google",
                "Google Workspace",
                "Drive, Docs, Sheets, Gmail, and Calendar",
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
