package com.example.integration.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAgentServiceTest {

    @Mock
    UserAgentRepository repository;

    UserAgentService service;

    @BeforeEach
    void setUp() {
        service = new UserAgentService(repository, Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createBuildsSystemPromptAndPersists() {
        when(repository.findByOwner("alice")).thenReturn(List.of());
        doNothing().when(repository).insert(any());

        UserAgent agent = service.create("alice", "Researcher", "Summarize code changes", "openai/gpt-oss-120b");

        ArgumentCaptor<UserAgent> captor = ArgumentCaptor.forClass(UserAgent.class);
        verify(repository).insert(captor.capture());
        UserAgent saved = captor.getValue();
        assertThat(saved.ownerUsername()).isEqualTo("alice");
        assertThat(saved.name()).isEqualTo("Researcher");
        assertThat(saved.purpose()).contains("Summarize code changes");
        assertThat(saved.systemPrompt()).contains("You are Researcher");
        assertThat(saved.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(agent.id()).isEqualTo(saved.id());
    }

    @Test
    void updatePreservesCreatedAt() {
        UserAgent existing = new UserAgent(
                "agent-1",
                "alice",
                "Researcher",
                "Summarize code changes",
                "old prompt",
                null,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"));
        when(repository.findByIdAndOwner("agent-1", "alice")).thenReturn(Optional.of(existing));
        when(repository.findByOwner("alice")).thenReturn(List.of(existing));
        when(repository.update(any())).thenReturn(1);

        UserAgent updated = service.update("alice", "agent-1", "Editor", "Review diffs", null);

        assertThat(updated.id()).isEqualTo("agent-1");
        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        assertThat(updated.systemPrompt()).contains("You are Editor");
        verify(repository).update(any());
    }

    @Test
    void createRejectsDuplicateNamesPerOwner() {
        when(repository.findByOwner("alice")).thenReturn(List.of(
                new UserAgent("agent-1", "alice", "Researcher", "one", "prompt", null, Instant.EPOCH, Instant.EPOCH)));

        assertThatThrownBy(() -> service.create("alice", "Researcher", "Different purpose", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).insert(any());
    }
}
