package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Tests discovery upsert/stale reconciliation and collision-free hugin-name generation. */
class McpDiscoveryServiceTest extends AbstractMcpDbTest {

    private McpHttpClient httpClient;
    private McpDiscoveryService discovery;

    @BeforeEach
    void setUpDiscovery() {
        httpClient = Mockito.mock(McpHttpClient.class);
        McpConnectionService connectionService =
                new McpConnectionService(serverRepository, toolRepository, encryption, httpClient, clock);
        discovery = new McpDiscoveryService(toolRepository, connectionService, httpClient, objectMapper, clock);
    }

    private JsonNode schema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    @Test
    void discoveryInsertsTools() throws Exception {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        when(httpClient.listTools(any())).thenReturn(List.of(
                new McpHttpClient.DiscoveredTool("create_issue", "Create an issue", schema()),
                new McpHttpClient.DiscoveredTool("list_issues", "List issues", schema())));

        McpDiscoveryResponse response = discovery.discover(server);

        assertThat(response.success()).isTrue();
        assertThat(response.discoveredCount()).isEqualTo(2);
        List<McpServerToolEntity> tools = toolRepository.findByServer(server.id());
        assertThat(tools).extracting(McpServerToolEntity::huginToolName)
                .containsExactlyInAnyOrder("mcp_linear_create_issue", "mcp_linear_list_issues");
        assertThat(tools).allMatch(McpServerToolEntity::enabled);
    }

    @Test
    void rediscoveryUpdatesSchemaButPreservesEnabledState() throws Exception {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        when(httpClient.listTools(any())).thenReturn(List.of(
                new McpHttpClient.DiscoveredTool("create_issue", "v1", schema())));
        discovery.discover(server);

        // User disables the tool, then the server re-advertises it with a new description.
        McpServerToolEntity tool = toolRepository.findByServer(server.id()).get(0);
        toolRepository.setEnabled(tool.id(), false);
        when(httpClient.listTools(any())).thenReturn(List.of(
                new McpHttpClient.DiscoveredTool("create_issue", "v2-updated", schema())));

        discovery.discover(server);

        McpServerToolEntity refreshed = toolRepository.findByServer(server.id()).get(0);
        assertThat(refreshed.enabled()).isFalse();
        assertThat(refreshed.description()).isEqualTo("v2-updated");
        assertThat(refreshed.huginToolName()).isEqualTo(tool.huginToolName());
        assertThat(refreshed.stale()).isFalse();
    }

    @Test
    void disappearedToolIsMarkedStaleNotDeleted() throws Exception {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        when(httpClient.listTools(any())).thenReturn(List.of(
                new McpHttpClient.DiscoveredTool("create_issue", "x", schema()),
                new McpHttpClient.DiscoveredTool("list_issues", "y", schema())));
        discovery.discover(server);

        // Second discovery only returns one of the two tools.
        when(httpClient.listTools(any())).thenReturn(List.of(
                new McpHttpClient.DiscoveredTool("create_issue", "x", schema())));
        discovery.discover(server);

        List<McpServerToolEntity> tools = toolRepository.findByServer(server.id());
        assertThat(tools).hasSize(2); // not deleted
        McpServerToolEntity gone = tools.stream()
                .filter(t -> t.toolName().equals("list_issues")).findFirst().orElseThrow();
        assertThat(gone.stale()).isTrue();
    }

    @Test
    void discoveryFailureReturnsReadableErrorAndInsertsNothing() throws Exception {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        when(httpClient.listTools(any()))
                .thenThrow(new McpHttpClient.McpClientException("Could not reach MCP server"));

        McpDiscoveryResponse response = discovery.discover(server);

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("Could not reach MCP server");
        assertThat(toolRepository.findByServer(server.id())).isEmpty();
    }

    @Test
    void huginNameCollisionAcrossServersIsDisambiguated() {
        insertUser("alice");
        McpServerEntity a = newServer("alice", "linear", McpAuthType.NONE, null);
        McpServerEntity b = newServer("alice", "linear-2", McpAuthType.NONE, null);
        serverRepository.insert(a);
        serverRepository.insert(b);
        // Seed an existing tool occupying the base name.
        toolRepository.insert(newTool(a.id(), "create_issue", "mcp_linear_create_issue", true, false));

        String generated = discovery.generateHuginToolName("linear", "create_issue", b.id());
        assertThat(generated).isNotEqualTo("mcp_linear_create_issue");
        assertThat(generated).startsWith("mcp_linear_create_issue");
    }
}
