package com.example.integration.controller;

import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubConnectResponse;
import com.example.integration.github.GitHubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GitHubControllerTest {

    @Mock
    private GitHubAppService github;

    @InjectMocks
    private GitHubController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusReturnsGitHubState() throws Exception {
        when(github.status()).thenReturn(new GitHubStatus(
                true, true, true, "github-app", "octocat",
                "Connected to GitHub as a GitHub App (octocat)."));

        mockMvc.perform(get("/api/github/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.authMode").value("github-app"))
                .andExpect(jsonPath("$.account").value("octocat"));
    }

    @Test
    void repositoriesReturnsAccessibleRepositories() throws Exception {
        when(github.listRepositories()).thenReturn(java.util.List.of(
                new GitHubAppService.GitHubRepositoryRef(
                        "octocat/hello-world", "hello-world", "octocat", true, "main", "Example repo")));

        mockMvc.perform(get("/api/github/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("octocat/hello-world"))
                .andExpect(jsonPath("$[0].defaultBranch").value("main"))
                .andExpect(jsonPath("$[0].privateRepo").value(true));
    }

    @Test
    void branchesReturnsRepositoryBranches() throws Exception {
        when(github.listBranches("octocat", "hello-world")).thenReturn(java.util.List.of("main", "develop"));

        mockMvc.perform(get("/api/github/repositories/octocat/hello-world/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("main"))
                .andExpect(jsonPath("$[1].name").value("develop"));
    }

    @Test
    void repositoryReturnsRepositoryDetails() throws Exception {
        when(github.repositoryDetails("octocat", "hello-world")).thenReturn(
                new GitHubAppService.GitHubRepositoryDetail(
                        "octocat/hello-world", "hello-world", "octocat", false, "main",
                        "Example repo", "TypeScript", 42, 7, 3,
                        "https://github.com/octocat/hello-world", "2026-06-26T00:00:00Z"));

        mockMvc.perform(get("/api/github/repositories/octocat/hello-world"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("octocat/hello-world"))
                .andExpect(jsonPath("$.language").value("TypeScript"))
                .andExpect(jsonPath("$.stargazers").value(42))
                .andExpect(jsonPath("$.defaultBranch").value("main"))
                .andExpect(jsonPath("$.privateRepo").value(false));
    }

    @Test
    void callbackRedirectsBackToState() throws Exception {
        mockMvc.perform(get("/api/github/callback")
                        .param("state", "http://localhost:5173/chat?tab=repo#composer")
                        .param("installation_id", "123")
                        .param("setup_action", "install"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "http://localhost:5173/chat?tab=repo&github=installed&installation_id=123&setup_action=install#composer"));

        verify(github).refresh();
    }

    @Test
    void setupRedirectFallsBackToIntegrations() throws Exception {
        mockMvc.perform(get("/api/github/setup"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?screen=integrations&github=installed"));

        verify(github).refresh();
    }

    @Test
    void setupRedirectSupportsRelativeState() throws Exception {
        mockMvc.perform(get("/api/github/setup")
                        .param("state", "/settings?view=integrations"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/settings?view=integrations&github=installed"));

        verify(github).refresh();
    }

    @Test
    void connectReturnsInstallUrl() throws Exception {
        when(github.beginConnect("http://localhost:5173/dashboard")).thenReturn(new GitHubConnectResponse(
                new GitHubStatus(false, true, true, "github-app", "",
                        "GitHub App is configured but not installed yet."),
                "https://github.com/apps/hugin/installations/new?state=x"));

        mockMvc.perform(post("/api/github/connect")
                        .contentType("application/json")
                        .content("{\"returnTo\":\"http://localhost:5173/dashboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.configured").value(true))
                .andExpect(jsonPath("$.installUrl").value("https://github.com/apps/hugin/installations/new?state=x"));
    }

    @Test
    void disconnectReturnsLatestStatus() throws Exception {
        when(github.disconnect()).thenReturn(new GitHubStatus(
                false, true, true, "github-app", "",
                "GitHub App is configured but not installed yet."));

        mockMvc.perform(post("/api/github/disconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.authMode").value("github-app"));
    }
}
