package com.example.integration.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Postgres-backed {@link GitHubWorkspaceStore} over the {@code github_workspaces} table. */
@Repository
public class JdbcGitHubWorkspaceStore implements GitHubWorkspaceStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcGitHubWorkspaceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(Record record) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                update github_workspaces
                set repo_full_name = ?, branch = ?, clone_url = ?, workspace_path = ?, updated_at = ?
                where sandbox_id = ?
                """,
                record.repoFullName(), record.branch(), record.cloneUrl(), record.workspacePath(),
                Timestamp.from(now), record.sandboxId());
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into github_workspaces
                        (sandbox_id, repo_full_name, branch, clone_url, workspace_path, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.sandboxId(), record.repoFullName(), record.branch(), record.cloneUrl(),
                    record.workspacePath(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    @Override
    public Optional<Record> find(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                select sandbox_id, repo_full_name, branch, clone_url, workspace_path
                from github_workspaces
                where sandbox_id = ?
                """,
                (rs, rowNum) -> new Record(
                        rs.getString("sandbox_id"),
                        rs.getString("repo_full_name"),
                        rs.getString("branch"),
                        rs.getString("clone_url"),
                        rs.getString("workspace_path")),
                sandboxId).stream().findFirst();
    }

    @Override
    public void delete(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            return;
        }
        jdbcTemplate.update("delete from github_workspaces where sandbox_id = ?", sandboxId);
    }
}
