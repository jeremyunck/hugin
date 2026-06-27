package com.example.integration.mcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Persistence for short-lived OAuth Authorization Code + PKCE requests ({@code mcp_oauth_states}). */
@Repository
public class McpOAuthStateRepository {

    /** One in-flight authorization request. */
    public record State(String state, String serverId, String ownerUsername, String codeVerifier,
                        String redirectUri, Instant createdAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public McpOAuthStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(State state) {
        jdbcTemplate.update(
                """
                        insert into mcp_oauth_states (state, server_id, owner_username, code_verifier, redirect_uri, created_at)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                state.state(), state.serverId(), state.ownerUsername(), state.codeVerifier(),
                state.redirectUri(), Timestamp.from(state.createdAt()));
    }

    public Optional<State> find(String state) {
        return jdbcTemplate.query(
                        """
                                select state, server_id, owner_username, code_verifier, redirect_uri, created_at
                                from mcp_oauth_states where state = ?
                                """,
                        this::mapRow, state)
                .stream().findFirst();
    }

    public void delete(String state) {
        jdbcTemplate.update("delete from mcp_oauth_states where state = ?", state);
    }

    /** Removes authorization requests older than the cutoff (called opportunistically by the callback). */
    public void deleteOlderThan(Instant cutoff) {
        jdbcTemplate.update("delete from mcp_oauth_states where created_at < ?", Timestamp.from(cutoff));
    }

    private State mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new State(
                rs.getString("state"),
                rs.getString("server_id"),
                rs.getString("owner_username"),
                rs.getString("code_verifier"),
                rs.getString("redirect_uri"),
                created == null ? null : created.toInstant());
    }
}
