package com.example.integration.agent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserAgentRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserAgentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UserAgent> findByOwner(String ownerUsername) {
        return jdbcTemplate.query(
                """
                        select id, owner_username, name, purpose, system_prompt, model, created_at, updated_at
                        from user_agents
                        where owner_username = ?
                        order by created_at desc, name asc
                        """,
                this::mapRow,
                ownerUsername);
    }

    public Optional<UserAgent> findByIdAndOwner(String id, String ownerUsername) {
        return jdbcTemplate.query(
                        """
                                select id, owner_username, name, purpose, system_prompt, model, created_at, updated_at
                                from user_agents
                                where id = ? and owner_username = ?
                                """,
                        this::mapRow,
                        id, ownerUsername)
                .stream()
                .findFirst();
    }

    public void insert(UserAgent agent) {
        try {
            jdbcTemplate.update(
                    """
                            insert into user_agents
                                (id, owner_username, name, purpose, system_prompt, model, created_at, updated_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    agent.id(),
                    agent.ownerUsername(),
                    agent.name(),
                    agent.purpose(),
                    agent.systemPrompt(),
                    agent.model(),
                    timestamp(agent.createdAt()),
                    timestamp(agent.updatedAt()));
        } catch (DuplicateKeyException ex) {
            throw ex;
        }
    }

    public int update(UserAgent agent) {
        return jdbcTemplate.update(
                """
                        update user_agents
                        set name = ?,
                            purpose = ?,
                            system_prompt = ?,
                            model = ?,
                            updated_at = ?
                        where id = ? and owner_username = ?
                        """,
                agent.name(),
                agent.purpose(),
                agent.systemPrompt(),
                agent.model(),
                timestamp(agent.updatedAt()),
                agent.id(),
                agent.ownerUsername());
    }

    public boolean delete(String id, String ownerUsername) {
        return jdbcTemplate.update(
                "delete from user_agents where id = ? and owner_username = ?",
                id, ownerUsername) > 0;
    }

    private UserAgent mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserAgent(
                rs.getString("id"),
                rs.getString("owner_username"),
                rs.getString("name"),
                rs.getString("purpose"),
                rs.getString("system_prompt"),
                rs.getString("model"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
