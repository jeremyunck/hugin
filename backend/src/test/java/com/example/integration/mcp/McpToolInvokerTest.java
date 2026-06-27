package com.example.integration.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests MCP tool execution: success path, error handling, auditing, and owner isolation. */
class McpToolInvokerTest extends AbstractMcpDbTest {

    private McpHttpClient httpClient;
    private McpToolInvoker invoker;

    @BeforeEach
    void setUpInvoker() {
        httpClient = Mockito.mock(McpHttpClient.class);
        McpConnectionService connectionService =
                new McpConnectionService(serverRepository, toolRepository, encryption, httpClient, clock);
        invoker = new McpToolInvoker(toolRepository, serverRepository, connectionService, httpClient,
                auditLogRepository, objectMapper, clock);
    }

    private McpServerToolEntity seedTool(String owner, String serverName, boolean enabled) {
        McpServerEntity server = newServer(owner, serverName, McpAuthType.BEARER_TOKEN, encryption.encrypt("tok"));
        serverRepository.insert(server);
        McpServerToolEntity tool = newTool(server.id(), "create_issue",
                "mcp_" + serverName + "_create_issue", enabled, false);
        toolRepository.insert(tool);
        return tool;
    }

    @Test
    void invocationSucceedsAndWritesAuditLog() throws Exception {
        insertUser("alice");
        McpServerToolEntity tool = seedTool("alice", "linear", true);
        when(httpClient.callTool(any(), eq("create_issue"), any())).thenReturn("Created issue #42");

        String result = invoker.invoke("alice", tool.huginToolName(), Map.of("title", "Bug"),
                "agent-1", "session-1");

        assertThat(result).isEqualTo("Created issue #42");
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from mcp_audit_log where owner_username = ? and status = 'success'",
                Integer.class, "alice");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void transportFailureReturnsReadableErrorAndAuditsError() throws Exception {
        insertUser("alice");
        McpServerToolEntity tool = seedTool("alice", "linear", true);
        when(httpClient.callTool(any(), any(), any()))
                .thenThrow(new McpHttpClient.McpClientException("HTTP 500"));

        String result = invoker.invoke("alice", tool.huginToolName(), Map.of(), "agent-1", "session-1");

        assertThat(result).contains("failed").contains("HTTP 500");
        Integer errors = jdbcTemplate.queryForObject(
                "select count(*) from mcp_audit_log where status = 'error'", Integer.class);
        assertThat(errors).isEqualTo(1);
    }

    @Test
    void anotherUsersToolIsNotInvokable() throws Exception {
        insertUser("alice");
        insertUser("bob");
        McpServerToolEntity tool = seedTool("alice", "linear", true);

        String result = invoker.invoke("bob", tool.huginToolName(), Map.of(), null, null);

        assertThat(result).contains("not available");
        verify(httpClient, never()).callTool(any(), any(), any());
    }

    @Test
    void disabledToolIsNotInvoked() throws Exception {
        insertUser("alice");
        McpServerToolEntity tool = seedTool("alice", "linear", false);

        String result = invoker.invoke("alice", tool.huginToolName(), Map.of(), null, null);

        assertThat(result).contains("disabled");
        verify(httpClient, never()).callTool(any(), any(), any());
    }
}
