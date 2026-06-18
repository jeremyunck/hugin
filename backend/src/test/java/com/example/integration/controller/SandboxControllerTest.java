package com.example.integration.controller;

import com.example.agent.model.SandboxInfo;
import com.example.integration.github.GitHubAppService;
import com.example.integration.service.DockerSandboxManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SandboxControllerTest {

    @Mock
    private DockerSandboxManager sandboxManager;

    @Mock
    private GitHubAppService github;

    @InjectMocks
    private SandboxController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createGitHubSandboxClonesSelectedBranch() throws Exception {
        SandboxInfo sandbox = new SandboxInfo(
                "sbx-1", "hugin-sbx-sbx-1", "ubuntu:24.04", SandboxInfo.RUNNING, Instant.now(), "/tmp/sbx-1/workspace");
        when(github.installationToken()).thenReturn(Optional.of("token-123"));
        when(github.cloneUrl("octocat/hello-world")).thenReturn("https://github.com/octocat/hello-world.git");
        when(sandboxManager.createGitHubRepoSandbox(
                eq(null), eq("https://github.com/octocat/hello-world.git"), eq("hello-world"), eq("develop"), eq("token-123")))
                .thenReturn(sandbox);

        mockMvc.perform(post("/api/sandboxes/github")
                        .contentType("application/json")
                        .content("{\"repoFullName\":\"octocat/hello-world\",\"branch\":\"develop\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sbx-1"))
                .andExpect(jsonPath("$.workspace").value("/tmp/sbx-1/workspace"));

        verify(github).installationToken();
        verify(github).cloneUrl("octocat/hello-world");
    }
}
