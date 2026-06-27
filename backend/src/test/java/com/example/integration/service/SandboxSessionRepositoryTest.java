package com.example.integration.service;

import com.example.agent.sandbox.SandboxSession;
import com.example.agent.sandbox.SandboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** H2-backed tests for {@link SandboxSessionRepository} persistence, lookup, and idle expiry. */
class SandboxSessionRepositoryTest {

    private SandboxSessionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sbx-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        repository = new SandboxSessionRepository(new JdbcTemplate(dataSource));
    }

    private SandboxSession session(UUID id, UUID chatId, SandboxStatus status, Instant expiresAt) {
        Instant now = Instant.now();
        return new SandboxSession(id, chatId, "cid-" + id, "bouw-agent-" + id, "bouw-agent-" + id + "-workspace",
                "https://github.com/octo/repo.git", "main", "/workspace/repo", status, now, now, expiresAt);
    }

    @Test
    void savesAndReadsBackById() {
        UUID id = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        SandboxSession saved = session(id, chatId, SandboxStatus.READY, Instant.now().plus(Duration.ofHours(72)));
        repository.save(saved);

        SandboxSession loaded = repository.findById(id.toString()).orElseThrow();
        assertThat(loaded.containerName()).isEqualTo("bouw-agent-" + id);
        assertThat(loaded.dockerVolumeName()).isEqualTo("bouw-agent-" + id + "-workspace");
        assertThat(loaded.repositoryUrl()).isEqualTo("https://github.com/octo/repo.git");
        assertThat(loaded.repositoryPath()).isEqualTo("/workspace/repo");
        assertThat(loaded.status()).isEqualTo(SandboxStatus.READY);
        assertThat(repository.findByChatSessionId(chatId.toString())).isPresent();
    }

    @Test
    void saveUpdatesExistingRowAndStatus() {
        UUID id = UUID.randomUUID();
        repository.save(session(id, null, SandboxStatus.CREATING, null));
        repository.updateStatus(id.toString(), SandboxStatus.READY);

        assertThat(repository.findById(id.toString()).orElseThrow().status()).isEqualTo(SandboxStatus.READY);
    }

    @Test
    void findExpiredReturnsOnlyPastDueNonDestroyedSessions() {
        UUID expired = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID destroyed = UUID.randomUUID();
        repository.save(session(expired, null, SandboxStatus.READY, Instant.now().minus(Duration.ofHours(1))));
        repository.save(session(fresh, null, SandboxStatus.READY, Instant.now().plus(Duration.ofHours(1))));
        repository.save(session(destroyed, null, SandboxStatus.DESTROYED, Instant.now().minus(Duration.ofHours(5))));

        var expiredIds = repository.findExpired(Instant.now()).stream().map(s -> s.id().toString()).toList();

        assertThat(expiredIds).containsExactly(expired.toString());
    }

    @Test
    void touchExtendsExpiry() {
        UUID id = UUID.randomUUID();
        repository.save(session(id, null, SandboxStatus.READY, Instant.now().minus(Duration.ofHours(1))));
        Instant newExpiry = Instant.now().plus(Duration.ofHours(72));

        repository.touch(id.toString(), Instant.now(), newExpiry);

        assertThat(repository.findExpired(Instant.now())).isEmpty();
    }

    @Test
    void deleteRemovesRow() {
        UUID id = UUID.randomUUID();
        repository.save(session(id, null, SandboxStatus.READY, null));
        repository.delete(id.toString());
        assertThat(repository.findById(id.toString())).isEmpty();
    }
}
