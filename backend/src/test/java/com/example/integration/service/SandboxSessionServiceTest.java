package com.example.integration.service;

import com.example.agent.sandbox.RepositoryConfig;
import com.example.agent.sandbox.SandboxRuntime;
import com.example.agent.sandbox.SandboxSession;
import com.example.agent.sandbox.SandboxStatus;
import com.example.integration.github.GitHubAppService;
import com.example.integration.sandbox.ProjectSandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestration tests for {@link SandboxSessionService} (Docker runtime + persistence both mocked). */
class SandboxSessionServiceTest {

    private DockerSandboxRuntime runtime;
    private SandboxSessionRepository repository;
    private SandboxSessionService service;

    private ProjectSandboxProperties props(boolean enabled) {
        return new ProjectSandboxProperties(enabled, "hugin-agent-sandbox:latest", 72, "docker",
                "4g", "2", 512, "", "hugin-agent-", "/workspace", "repo", null, null, null);
    }

    @BeforeEach
    void setUp() {
        runtime = mock(DockerSandboxRuntime.class);
        repository = mock(SandboxSessionRepository.class);
        service = new SandboxSessionService(runtime, repository, props(true));
    }

    private SandboxSession session(SandboxStatus status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new SandboxSession(id, null, "cid", "hugin-agent-" + id, "hugin-agent-" + id + "-workspace",
                "https://github.com/octo/repo.git", "main", "/workspace/repo", status, now, now,
                now.plus(Duration.ofHours(72)));
    }

    @Test
    void createForChatProvisionsAndPersists() {
        SandboxSession created = session(SandboxStatus.READY);
        when(runtime.create(eq("chat-1"), any(RepositoryConfig.class))).thenReturn(created);
        RepositoryConfig repo = new RepositoryConfig("https://github.com/octo/repo.git", "octo/repo", "main", "tok");

        SandboxSession result = service.createForChat("chat-1", repo);

        assertThat(result).isEqualTo(created);
        verify(runtime).create("chat-1", repo);
        verify(repository).save(created);
    }

    @Test
    void createForChatFailsLoudlyWhenDisabled() {
        service = new SandboxSessionService(runtime, repository, props(false));
        RepositoryConfig repo = new RepositoryConfig("u", "octo/repo", "main", "tok");

        assertThatThrownBy(() -> service.createForChat("chat-1", repo))
                .isInstanceOf(IllegalStateException.class);
        verify(runtime, never()).create(any(), any());
    }

    @Test
    void reconnectRestartsStoppedContainer() {
        SandboxSession stored = session(SandboxStatus.STOPPED);
        String id = stored.sandboxId();
        when(repository.findById(id)).thenReturn(Optional.of(stored));
        when(runtime.inspect(id)).thenReturn(
                new SandboxRuntime.SandboxState(id, "cid", SandboxStatus.STOPPED, false));

        SandboxSession result = service.reconnect(id).orElseThrow();

        verify(runtime).restart(id);
        assertThat(result.status()).isEqualTo(SandboxStatus.READY);
    }

    @Test
    void reconnectRefreshesGitCredentialsWithFreshToken() {
        GitHubAppService github = mock(GitHubAppService.class);
        when(github.installationToken()).thenReturn(Optional.of("fresh-token"));
        service = new SandboxSessionService(runtime, repository, props(true), Optional.of(github));

        SandboxSession stored = session(SandboxStatus.READY);
        String id = stored.sandboxId();
        when(repository.findById(id)).thenReturn(Optional.of(stored));
        when(runtime.inspect(id)).thenReturn(
                new SandboxRuntime.SandboxState(id, "cid", SandboxStatus.READY, true));

        SandboxSession result = service.reconnect(id).orElseThrow();

        // A fresh installation token is re-persisted into the container's credential helper so the
        // resumed chat can still push after the clone-time token has expired.
        verify(runtime).refreshCredentials(id, "fresh-token");
        assertThat(result.status()).isEqualTo(SandboxStatus.READY);
    }

    @Test
    void reconnectSkipsCredentialRefreshWhenNoTokenAvailable() {
        GitHubAppService github = mock(GitHubAppService.class);
        when(github.installationToken()).thenReturn(Optional.empty());
        service = new SandboxSessionService(runtime, repository, props(true), Optional.of(github));

        SandboxSession stored = session(SandboxStatus.READY);
        String id = stored.sandboxId();
        when(repository.findById(id)).thenReturn(Optional.of(stored));
        when(runtime.inspect(id)).thenReturn(
                new SandboxRuntime.SandboxState(id, "cid", SandboxStatus.READY, true));

        service.reconnect(id).orElseThrow();

        verify(runtime, never()).refreshCredentials(any(), any());
    }

    @Test
    void reconnectMarksDestroyedWhenContainerGone() {
        SandboxSession stored = session(SandboxStatus.READY);
        String id = stored.sandboxId();
        when(repository.findById(id)).thenReturn(Optional.of(stored));
        when(runtime.inspect(id)).thenReturn(
                new SandboxRuntime.SandboxState(id, null, SandboxStatus.DESTROYED, false));

        SandboxSession result = service.reconnect(id).orElseThrow();

        assertThat(result.status()).isEqualTo(SandboxStatus.DESTROYED);
        verify(runtime, never()).restart(id);
    }

    @Test
    void reconnectMissingSessionIsEmpty() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.reconnect("nope")).isEmpty();
    }

    @Test
    void deleteDestroysContainerAndRemovesRecord() {
        service.delete("sbx-1");
        verify(runtime).delete("sbx-1");
        verify(repository).delete("sbx-1");
    }

    @Test
    void destroyExpiredDeletesEachExpiredSandbox() {
        SandboxSession a = session(SandboxStatus.READY);
        SandboxSession b = session(SandboxStatus.STOPPED);
        when(repository.findExpired(any())).thenReturn(List.of(a, b));

        int destroyed = service.destroyExpired();

        assertThat(destroyed).isEqualTo(2);
        verify(runtime).delete(a.sandboxId());
        verify(runtime).delete(b.sandboxId());
        verify(repository).delete(a.sandboxId());
        verify(repository).delete(b.sandboxId());
    }
}
