package com.example.integration.mcp;

import com.example.agent.tool.LocalTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests owner-scoped advertisement gating: only enabled, non-stale tools on enabled, owned servers. */
class McpToolRegistryTest extends AbstractMcpDbTest {

    private McpToolRegistry registry;

    @BeforeEach
    void setUpRegistry() {
        McpToolInvoker invoker = Mockito.mock(McpToolInvoker.class);
        registry = new McpToolRegistry(serverRepository, toolRepository, invoker, objectMapper);
    }

    private List<String> advertisedNames(String owner) {
        return registry.tools(owner).stream().map(LocalTool::name).toList();
    }

    @Test
    void advertisesEnabledToolsOnly() {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        toolRepository.insert(newTool(server.id(), "create_issue", "mcp_linear_create_issue", true, false));
        toolRepository.insert(newTool(server.id(), "delete_issue", "mcp_linear_delete_issue", false, false));
        toolRepository.insert(newTool(server.id(), "old_tool", "mcp_linear_old_tool", true, true));

        assertThat(advertisedNames("alice")).containsExactly("mcp_linear_create_issue");
    }

    @Test
    void disabledServerAdvertisesNothing() {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        McpServerEntity disabled = new McpServerEntity(server.id(), server.ownerUsername(), server.name(),
                server.displayName(), server.transport(), server.endpointUrl(), server.authType(),
                server.accessTokenEncrypted(), false, server.createdAt(), server.updatedAt());
        serverRepository.insert(disabled);
        toolRepository.insert(newTool(server.id(), "create_issue", "mcp_linear_create_issue", true, false));

        assertThat(advertisedNames("alice")).isEmpty();
    }

    @Test
    void ownersDoNotSeeEachOthersTools() {
        insertUser("alice");
        insertUser("bob");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        toolRepository.insert(newTool(server.id(), "create_issue", "mcp_linear_create_issue", true, false));

        assertThat(advertisedNames("alice")).containsExactly("mcp_linear_create_issue");
        assertThat(advertisedNames("bob")).isEmpty();
        // find() is owner-scoped too.
        assertThat(registry.find("bob", "mcp_linear_create_issue")).isNull();
        assertThat(registry.find("alice", "mcp_linear_create_issue")).isNotNull();
    }

    @Test
    void findRejectsDisabledTool() {
        insertUser("alice");
        McpServerEntity server = newServer("alice", "linear", McpAuthType.NONE, null);
        serverRepository.insert(server);
        toolRepository.insert(newTool(server.id(), "create_issue", "mcp_linear_create_issue", false, false));

        assertThat(registry.find("alice", "mcp_linear_create_issue")).isNull();
    }
}
