package com.example.integration.mcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Append-only persistence for {@link McpAuditLogEntity}. */
@Repository
public class McpAuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public McpAuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(McpAuditLogEntity entry) {
        jdbcTemplate.update(
                """
                        insert into mcp_audit_log
                            (id, owner_username, agent_id, session_id, server_id, tool_name,
                             arguments_json, result_preview, status, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                entry.id(),
                entry.ownerUsername(),
                entry.agentId(),
                entry.sessionId(),
                entry.serverId(),
                entry.toolName(),
                entry.argumentsJson(),
                entry.resultPreview(),
                entry.status(),
                timestamp(entry.createdAt()));
    }

    public List<McpAuditLogEntity> findByOwner(String ownerUsername, int limit) {
        return jdbcTemplate.query(
                """
                        select id, owner_username, agent_id, session_id, server_id, tool_name,
                               arguments_json, result_preview, status, created_at
                        from mcp_audit_log
                        where owner_username = ?
                        order by created_at desc
                        limit ?
                        """,
                this::mapRow,
                ownerUsername, limit);
    }

    private McpAuditLogEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new McpAuditLogEntity(
                rs.getString("id"),
                rs.getString("owner_username"),
                rs.getString("agent_id"),
                rs.getString("session_id"),
                rs.getString("server_id"),
                rs.getString("tool_name"),
                rs.getString("arguments_json"),
                rs.getString("result_preview"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
