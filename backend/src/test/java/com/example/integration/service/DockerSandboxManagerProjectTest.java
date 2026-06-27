package com.example.integration.service;

import com.example.agent.model.SandboxInfo;
import com.example.agent.sandbox.RepositoryConfig;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.sandbox.SandboxSession;
import com.example.agent.sandbox.SandboxStatus;
import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.example.integration.sandbox.SandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the isolated, containerized project-chat flow: {@link DockerSandboxManager} provisions a
 * sandbox via the {@link SandboxSessionService}, routes the agent's tools into the container, fails
 * loudly when no isolated runtime is available (no host fallback), and reconnects a resumed chat.
 */
class DockerSandboxManagerProjectTest {

    @TempDir
    Path tmp;

    private static final String CLONE_URL = "https://github.com/octo/origin.git";

    private WorkspaceRegistry newRegistry() {
        Workspace defaultWorkspace = new Workspace(new LocalToolProperties(
                true, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of()));
        return new WorkspaceRegistry(defaultWorkspace);
    }

    private SandboxProperties props() {
        return new SandboxProperties(true, "ubuntu:24.04", "/missing/docker",
                Duration.ofSeconds(10), Duration.ofSeconds(10), "", "bouw-sbx-", 25);
    }

    private SandboxSession session(String repoPath) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new SandboxSession(id, null, "container-id", "bouw-agent-" + id, "bouw-agent-" + id + "-workspace",
                CLONE_URL, "main", repoPath, SandboxStatus.READY, now, now, now.plus(Duration.ofHours(72)));
    }

    private DockerSandboxManager manager(WorkspaceRegistry registry,
                                         SandboxSessionService sessions,
                                         DockerSandboxRuntime runtime) {
        DockerSandboxManager manager = new DockerSandboxManager(
                props(), registry, new WorkspaceFactory(), Optional.empty(), Optional.empty(),
                Optional.ofNullable(sessions), Optional.ofNullable(runtime), tmp.toString());
        registry.setRehydrator(manager);
        return manager;
    }

    @Test
    void createGitHubRepoSandboxProvisionsContainerAndRoutesToolsIntoIt() {
        WorkspaceRegistry registry = newRegistry();
        SandboxSessionService sessions = mock(SandboxSessionService.class);
        DockerSandboxRuntime runtime = mock(DockerSandboxRuntime.class);
        SandboxSession session = session("/workspace/repo");
        when(sessions.enabled()).thenReturn(true);
        when(sessions.createForChat(isNull(), any(RepositoryConfig.class))).thenReturn(session);
        DockerSandboxManager manager = manager(registry, sessions, runtime);

        SandboxInfo info = manager.createGitHubRepoSandbox(null, CLONE_URL, "octo/origin", "main", "tok", null);

        assertThat(info.id()).isEqualTo(session.sandboxId());
        assertThat(info.workspace()).isEqualTo("/workspace/repo");
        assertThat(info.status()).isEqualTo(SandboxInfo.RUNNING);
        // The chat's tools are now container-bound: a container WorkspaceContext (host access denied)
        // and the repository context are registered under the sandbox id.
        assertThat(registry.containerContext(session.sandboxId())).isPresent();
        assertThat(registry.containerContext(session.sandboxId()).get().hostAccessAllowed()).isFalse();
        assertThat(registry.containerContext(session.sandboxId()).get().repositoryPath()).isEqualTo("/workspace/repo");
        assertThat(registry.githubRepo(session.sandboxId())).contains("octo/origin");
        verify(sessions).createForChat(isNull(), any(RepositoryConfig.class));
    }

    @Test
    void stagesBugReportInsideContainer() {
        WorkspaceRegistry registry = newRegistry();
        SandboxSessionService sessions = mock(SandboxSessionService.class);
        DockerSandboxRuntime runtime = mock(DockerSandboxRuntime.class);
        SandboxSession session = session("/workspace/repo");
        when(sessions.enabled()).thenReturn(true);
        when(sessions.createForChat(isNull(), any(RepositoryConfig.class))).thenReturn(session);
        DockerSandboxManager manager = manager(registry, sessions, runtime);

        var bugReport = new BugReportCatalogService.StoredBugReport(
                "bug-1", "Hung chat", "session-1", null, null,
                "bug-reports/2026-06-18/hung-chat.txt", "Bouw Bug Report\n\nBody", "2026-06-18T14:05:06Z");

        manager.createGitHubRepoSandbox(null, CLONE_URL, "octo/origin", "main", "tok", bugReport);

        verify(runtime).writeFile(session.sandboxId(), "bug-reports/2026-06-18/hung-chat.txt", "Bouw Bug Report\n\nBody");
    }

    @Test
    void execRoutesProjectChatCommandsIntoContainer() throws Exception {
        WorkspaceRegistry registry = newRegistry();
        SandboxSessionService sessions = mock(SandboxSessionService.class);
        DockerSandboxRuntime runtime = mock(DockerSandboxRuntime.class);
        SandboxSession session = session("/workspace/repo");
        when(sessions.enabled()).thenReturn(true);
        when(sessions.createForChat(isNull(), any(RepositoryConfig.class))).thenReturn(session);
        when(runtime.exec(eq(session.sandboxId()), eq("echo hi"), any()))
                .thenReturn(new SandboxRuntime.ExecResult(0, "hi", false));
        DockerSandboxManager manager = manager(registry, sessions, runtime);
        manager.createGitHubRepoSandbox(null, CLONE_URL, "octo/origin", "main", "tok", null);

        SandboxRuntime.ExecResult result = manager.exec(session.sandboxId(), "echo hi", Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("hi");
        verify(runtime).exec(eq(session.sandboxId()), eq("echo hi"), any());
    }

    @Test
    void deleteDestroysContainerAndUnregisters() {
        WorkspaceRegistry registry = newRegistry();
        SandboxSessionService sessions = mock(SandboxSessionService.class);
        DockerSandboxRuntime runtime = mock(DockerSandboxRuntime.class);
        SandboxSession session = session("/workspace/repo");
        when(sessions.enabled()).thenReturn(true);
        when(sessions.createForChat(isNull(), any(RepositoryConfig.class))).thenReturn(session);
        DockerSandboxManager manager = manager(registry, sessions, runtime);
        manager.createGitHubRepoSandbox(null, CLONE_URL, "octo/origin", "main", "tok", null);

        manager.delete(session.sandboxId());

        verify(sessions).delete(session.sandboxId());
        assertThat(registry.containerContext(session.sandboxId())).isEmpty();
    }

    @Test
    void reconnectsResumedChatViaRehydration() {
        WorkspaceRegistry registry = newRegistry();
        SandboxSessionService sessions = mock(SandboxSessionService.class);
        DockerSandboxRuntime runtime = mock(DockerSandboxRuntime.class);
        SandboxSession session = session("/workspace/repo");
        when(sessions.reconnect(session.sandboxId())).thenReturn(Optional.of(session));
        DockerSandboxManager manager = manager(registry, sessions, runtime);

        // A resumed chat resolves its workspace by sandbox id; the registry cache-miss triggers
        // rehydration, which reconnects to the container and re-registers the container context.
        registry.resolve(session.sandboxId());

        assertThat(registry.containerContext(session.sandboxId())).isPresent();
        assertThat(registry.githubRepo(session.sandboxId())).contains("octo/origin");
        verify(sessions).reconnect(session.sandboxId());
    }

    @Test
    void failsLoudlyWhenNoIsolatedRuntimeIsAvailable() {
        WorkspaceRegistry registry = newRegistry();
        // No SandboxSessionService / DockerSandboxRuntime wired: project chats must fail, not host-clone.
        DockerSandboxManager manager = manager(registry, null, null);

        assertThatThrownBy(() -> manager.createGitHubRepoSandbox(null, CLONE_URL, "octo/origin", "main", "tok", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no host fallback");
    }
}
