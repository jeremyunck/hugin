package com.example.integration.controller;

import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubStatus;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleWorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        IntegrationsController controller = new IntegrationsController(google, github, "");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
}
