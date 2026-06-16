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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
