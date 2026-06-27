package com.example.integration.mcp;

import com.example.integration.auth.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Shared H2 (PostgreSQL-mode) fixture for MCP persistence tests: loads {@code schema.sql}, builds the
 * repositories, and provides helpers to seed users and servers. Mirrors the approach used by
 * {@code SandboxSessionRepositoryTest}.
 */
abstract class AbstractMcpDbTest {

    protected JdbcTemplate jdbcTemplate;
    protected McpServerRepository serverRepository;
    protected McpServerToolRepository toolRepository;
    protected McpAuditLogRepository auditLogRepository;
    protected McpSecretEncryptionService encryption;
    protected ObjectMapper objectMapper;
    protected Clock clock;

    @BeforeEach
    void initDb() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:mcp-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbcTemplate = new JdbcTemplate(dataSource);
        serverRepository = new McpServerRepository(jdbcTemplate);
        toolRepository = new McpServerToolRepository(jdbcTemplate);
        auditLogRepository = new McpAuditLogRepository(jdbcTemplate);
        encryption = new McpSecretEncryptionService(new CredentialCipher("test-secret-key"));
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    /** Inserts a user so the {@code mcp_servers.owner_username} FK is satisfied. */
    protected void insertUser(String username) {
        jdbcTemplate.update(
                "insert into app_users (username, password_hash, enabled, roles) values (?, ?, true, 'ROLE_USER')",
                username, "hash");
    }

    protected McpServerEntity newServer(String owner, String name, McpAuthType authType, String encryptedToken) {
        Instant now = Instant.now(clock);
        return new McpServerEntity(
                UUID.randomUUID().toString(),
                owner,
                name,
                name + " display",
                McpTransport.STREAMABLE_HTTP,
                "https://example.com/mcp",
                authType,
                encryptedToken,
                true,
                now,
                now);
    }

    protected McpServerToolEntity newTool(String serverId, String toolName, String huginName,
                                          boolean enabled, boolean stale) {
        return new McpServerToolEntity(
                UUID.randomUUID().toString(),
                serverId,
                toolName,
                huginName,
                "desc",
                "{\"type\":\"object\",\"properties\":{}}",
                enabled,
                stale,
                Instant.now(clock));
    }
}
