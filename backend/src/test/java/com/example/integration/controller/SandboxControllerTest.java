package com.example.integration.controller;

import com.example.agent.model.SandboxInfo;
import com.example.integration.github.GitHubAppService;
import com.example.integration.service.BugReportCatalogService;
import com.example.integration.service.DockerSandboxManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SandboxControllerTest {

    @Mock
    private DockerSandboxManager sandboxManager;

    @Mock
    private GitHubAppService github;

    @Mock
    private BugReportCatalogService bugReportCatalogService;

    private SandboxController controller;

    @BeforeEach
    void setUp() {
        controller = new SandboxController(sandboxManager, github, bugReportCatalogService);
    }

    @Test
    void createGitHubSandboxClonesSelectedBranch() throws Exception {
        SandboxInfo sandbox = new SandboxInfo(
                "sbx-1", "bouw-sbx-sbx-1", "ubuntu:24.04", SandboxInfo.RUNNING, Instant.now(), "/tmp/sbx-1/workspace");
        when(github.installationToken()).thenReturn(Optional.of("token-123"));
        when(github.cloneUrl("octocat/hello-world")).thenReturn("https://github.com/octocat/hello-world.git");
        when(sandboxManager.createGitHubRepoSandbox(
                eq(null), eq("https://github.com/octocat/hello-world.git"), eq("octocat/hello-world"), eq("develop"), eq("token-123"), eq(null)))
                .thenReturn(sandbox);

        var result = controller.createGitHubSandbox(
                new SandboxController.CreateGitHubSandboxRequest(null, "octocat/hello-world", "develop", null),
                null);

        assertThat(result.getStatusCodeValue()).isEqualTo(201);
        assertThat(result.getBody()).isEqualTo(sandbox);

        verify(github).installationToken();
        verify(github).cloneUrl("octocat/hello-world");
    }

    @Test
    void createGitHubSandboxResolvesSelectedBugReport() throws Exception {
        SandboxInfo sandbox = new SandboxInfo(
                "sbx-1", "bouw-sbx-sbx-1", "ubuntu:24.04", SandboxInfo.RUNNING, Instant.now(), "/tmp/sbx-1/workspace");
        var bugReport = new BugReportCatalogService.StoredBugReport(
                "bug-123",
                "Hung chat",
                "session-1",
                null,
                null,
                "bug-reports/2026-06-18/report.txt",
                "body",
                "2026-06-18T14:05:06Z");
        when(github.installationToken()).thenReturn(Optional.of("token-123"));
        when(github.cloneUrl("octocat/hello-world")).thenReturn("https://github.com/octocat/hello-world.git");
        when(bugReportCatalogService.find("owner-1", "bug-123")).thenReturn(Optional.of(bugReport));
        when(sandboxManager.createGitHubRepoSandbox(
                eq(null), eq("https://github.com/octocat/hello-world.git"), eq("octocat/hello-world"), eq("develop"),
                eq("token-123"), eq(bugReport)))
                .thenReturn(sandbox);

        var jwt = org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.Jwt.class);
        when(jwt.getSubject()).thenReturn("owner-1");

        var result = controller.createGitHubSandbox(
                new SandboxController.CreateGitHubSandboxRequest(null, "octocat/hello-world", "develop", "bug-123"),
                jwt);

        assertThat(result.getStatusCodeValue()).isEqualTo(201);
        assertThat(result.getBody()).isEqualTo(sandbox);
        verify(bugReportCatalogService).find("owner-1", "bug-123");
    }
}
