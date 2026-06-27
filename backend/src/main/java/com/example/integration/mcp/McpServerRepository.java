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
 * Persistence for {@link McpServerEntity}. Every read and write is scoped by {@code ownerUsername} so
 * a user can only ever touch their own MCP servers.
 */
@Repository
public class McpServerRepository {

    private final JdbcTemplate jdbcTemplate;

    public McpServerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<McpServerEntity> findByOwner(String ownerUsername) {
        return jdbcTemplate.query(
                """
                        select id, owner_username, name, display_name, transport, endpoint_url, auth_type,
                               access_token_encrypted, enabled, created_at, updated_at, config_json
                        from mcp_servers
                        where owner_username = ?
                        order by created_at asc, display_name asc
                        """,
                this::mapRow,
                ownerUsername);
    }

    /** Enabled servers for an owner — the only ones whose tools may be advertised. */
    public List<McpServerEntity> findEnabledByOwner(String ownerUsername) {
        return jdbcTemplate.query(
                """
                        select id, owner_username, name, display_name, transport, endpoint_url, auth_type,
                               access_token_encrypted, enabled, created_at, updated_at, config_json
                        from mcp_servers
                        where owner_username = ? and enabled = true
                        order by created_at asc, display_name asc
                        """,
                this::mapRow,
                ownerUsername);
    }

    public Optional<McpServerEntity> findByIdAndOwner(String id, String ownerUsername) {
        return jdbcTemplate.query(
                        """
                                select id, owner_username, name, display_name, transport, endpoint_url, auth_type,
                                       access_token_encrypted, enabled, created_at, updated_at, config_json
                                from mcp_servers
                                where id = ? and owner_username = ?
                                """,
                        this::mapRow,
                        id, ownerUsername)
                .stream()
                .findFirst();
    }

    public void insert(McpServerEntity server) {
        jdbcTemplate.update(
                """
                        insert into mcp_servers
                            (id, owner_username, name, display_name, transport, endpoint_url, auth_type,
                             access_token_encrypted, enabled, created_at, updated_at, config_json)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                server.id(),
                server.ownerUsername(),
                server.name(),
                server.displayName(),
                server.transport().name(),
                server.endpointUrl(),
                server.authType().name(),
                server.accessTokenEncrypted(),
                server.enabled(),
                timestamp(server.createdAt()),
                timestamp(server.updatedAt()),
                server.configJson());
    }

    public int update(McpServerEntity server) {
        return jdbcTemplate.update(
                """
                        update mcp_servers
                        set display_name = ?,
                            transport = ?,
                            endpoint_url = ?,
                            auth_type = ?,
                            access_token_encrypted = ?,
                            enabled = ?,
                            updated_at = ?,
                            config_json = ?
                        where id = ? and owner_username = ?
                        """,
                server.displayName(),
                server.transport().name(),
                server.endpointUrl(),
                server.authType().name(),
                server.accessTokenEncrypted(),
                server.enabled(),
                timestamp(server.updatedAt()),
                server.configJson(),
                server.id(),
                server.ownerUsername());
    }

    /**
     * Persists only the {@code config_json} (used by OAuth token refresh, which must not disturb other
     * fields or require the owner on the call path).
     */
    public int updateConfigJson(String id, String configJson) {
        return jdbcTemplate.update(
                "update mcp_servers set config_json = ? where id = ?",
                configJson, id);
    }

    public boolean delete(String id, String ownerUsername) {
        return jdbcTemplate.update(
                "delete from mcp_servers where id = ? and owner_username = ?",
                id, ownerUsername) > 0;
    }

    private McpServerEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new McpServerEntity(
                rs.getString("id"),
                rs.getString("owner_username"),
                rs.getString("name"),
                rs.getString("display_name"),
                McpTransport.fromString(rs.getString("transport")),
                rs.getString("endpoint_url"),
                McpAuthType.fromString(rs.getString("auth_type")),
                rs.getString("access_token_encrypted"),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("config_json"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
