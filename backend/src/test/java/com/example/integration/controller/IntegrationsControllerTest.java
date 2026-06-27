package com.example.integration.controller;

import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubStatus;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleWorkspaceStatus;
import com.example.integration.mcp.McpConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class IntegrationsControllerTest {

    @Mock
    private GoogleWorkspaceClientFactory google;

    @Mock
    private GitHubAppService github;

    @Mock
    private McpConnectionService mcp;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // No MCP servers for these tests; the @AuthenticationPrincipal Jwt resolves to null in
        // standalone MockMvc, so the controller falls back to the "global" owner.
        when(mcp.listDtos(anyString())).thenReturn(List.of());
        IntegrationsController controller = new IntegrationsController(google, github, mcp, "");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void githubRemainsReconnectableWhileDisconnected() throws Exception {
        when(google.status()).thenReturn(new GoogleWorkspaceStatus(
                false, true, true, "oauth", "Google OAuth is not connected."));
        when(github.status()).thenReturn(new GitHubStatus(
                false, false, false, "github-app", "", "GitHub App is not configured."));

        mockMvc.perform(get("/api/integrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value("github"))
                .andExpect(jsonPath("$[1].connected").value(false))
                .andExpect(jsonPath("$[1].reconnectable").value(true));
    }

    @Test
    void googleListsDriveTools() throws Exception {
        when(google.status()).thenReturn(new GoogleWorkspaceStatus(
                true, true, true, "oauth", "Google OAuth is connected."));
        when(github.status()).thenReturn(new GitHubStatus(
                false, false, false, "github-app", "", "GitHub App is not configured."));

        mockMvc.perform(get("/api/integrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("google"))
                .andExpect(jsonPath("$[0].tools[0]").value("google_drive_search"))
                .andExpect(jsonPath("$[0].tools[1]").value("google_drive_read_file"));
    }

    @Test
    void githubListsPullRequestTool() throws Exception {
        when(google.status()).thenReturn(new GoogleWorkspaceStatus(
                false, true, true, "oauth", "Google OAuth is not connected."));
        when(github.status()).thenReturn(new GitHubStatus(
                true, true, true, "github-app", "octocat", "GitHub App is connected."));

        mockMvc.perform(get("/api/integrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value("github"))
                .andExpect(jsonPath("$[1].tools[2]").value("github_create_pull_request"));
    }
}
