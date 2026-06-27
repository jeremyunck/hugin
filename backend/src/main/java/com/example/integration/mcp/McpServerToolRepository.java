package com.example.integration.mcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link McpServerToolEntity}. Lookups are by {@code server_id} (owner isolation is
 * enforced one level up, since a server is already owner-scoped) or by the globally unique
 * {@code bouw_tool_name}.
 */
@Repository
public class McpServerToolRepository {

    private final JdbcTemplate jdbcTemplate;

    public McpServerToolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<McpServerToolEntity> findByServer(String serverId) {
        return jdbcTemplate.query(
                """
                        select id, server_id, tool_name, bouw_tool_name, description, input_schema_json,
                               enabled, stale, last_seen_at
                        from mcp_server_tools
                        where server_id = ?
                        order by tool_name asc
                        """,
                this::mapRow,
                serverId);
    }

    public Optional<McpServerToolEntity> findByServerAndToolName(String serverId, String toolName) {
        return jdbcTemplate.query(
                        """
                                select id, server_id, tool_name, bouw_tool_name, description, input_schema_json,
                                       enabled, stale, last_seen_at
                                from mcp_server_tools
                                where server_id = ? and tool_name = ?
                                """,
                        this::mapRow,
                        serverId, toolName)
                .stream()
                .findFirst();
    }

    public Optional<McpServerToolEntity> findByBouwToolName(String bouwToolName) {
        return jdbcTemplate.query(
                        """
                                select id, server_id, tool_name, bouw_tool_name, description, input_schema_json,
                                       enabled, stale, last_seen_at
                                from mcp_server_tools
                                where bouw_tool_name = ?
                                """,
                        this::mapRow,
                        bouwToolName)
                .stream()
                .findFirst();
    }

    public Optional<McpServerToolEntity> findByIdAndServer(String id, String serverId) {
        return jdbcTemplate.query(
                        """
                                select id, server_id, tool_name, bouw_tool_name, description, input_schema_json,
                                       enabled, stale, last_seen_at
                                from mcp_server_tools
                                where id = ? and server_id = ?
                                """,
                        this::mapRow,
                        id, serverId)
                .stream()
                .findFirst();
    }

    public void insert(McpServerToolEntity tool) {
        jdbcTemplate.update(
                """
                        insert into mcp_server_tools
                            (id, server_id, tool_name, bouw_tool_name, description, input_schema_json,
                             enabled, stale, last_seen_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tool.id(),
                tool.serverId(),
                tool.toolName(),
                tool.bouwToolName(),
                tool.description(),
                tool.inputSchemaJson(),
                tool.enabled(),
                tool.stale(),
                timestamp(tool.lastSeenAt()));
    }

    /**
     * Updates the schema/description and freshness of an existing tool while preserving its
     * {@code enabled} state. Used by discovery when a tool is seen again.
     */
    public int updateOnRediscovery(McpServerToolEntity tool) {
        return jdbcTemplate.update(
                """
                        update mcp_server_tools
                        set description = ?,
                            input_schema_json = ?,
                            stale = false,
                            last_seen_at = ?
                        where id = ?
                        """,
                tool.description(),
                tool.inputSchemaJson(),
                timestamp(tool.lastSeenAt()),
                tool.id());
    }

    /** Sets the enabled flag for a single tool (used by the tools toggle endpoint). */
    public int setEnabled(String id, boolean enabled) {
        return jdbcTemplate.update(
                "update mcp_server_tools set enabled = ? where id = ?",
                enabled, id);
    }

    /** Sets the stale flag for a single tool. A stale tool is kept but never advertised or invoked. */
    public int setStale(String id, boolean stale) {
        return jdbcTemplate.update(
                "update mcp_server_tools set stale = ? where id = ?",
                stale, id);
    }

    private McpServerToolEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new McpServerToolEntity(
                rs.getString("id"),
                rs.getString("server_id"),
                rs.getString("tool_name"),
                rs.getString("bouw_tool_name"),
                rs.getString("description"),
                rs.getString("input_schema_json"),
                rs.getBoolean("enabled"),
                rs.getBoolean("stale"),
                toInstant(rs.getTimestamp("last_seen_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
