package com.example.integration.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behavioural tests for {@link McpConnectionService}: encryption, token hiding, owner isolation, delete cascade. */
class McpConnectionServiceTest extends AbstractMcpDbTest {

    private McpConnectionService service() {
        McpHttpClient httpClient = Mockito.mock(McpHttpClient.class);
        return new McpConnectionService(serverRepository, toolRepository, encryption, httpClient, clock);
    }

    @Test
    void createEncryptsBearerTokenAtRest() {
        insertUser("alice");
        McpConnectionService service = service();

        McpServerEntity created = service.create("alice", new McpCreateRequest(
                "Linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "BEARER_TOKEN", "super-secret"));

        // Stored value is encrypted, never the plaintext.
        String storedRaw = jdbcTemplate.queryForObject(
                "select access_token_encrypted from mcp_servers where id = ?", String.class, created.id());
        assertThat(storedRaw).isNotNull().isNotEqualTo("super-secret").startsWith("v1:");
        assertThat(encryption.decrypt(storedRaw)).isEqualTo("super-secret");
        assertThat(created.name()).isEqualTo("linear");
    }

    @Test
    void dtoNeverExposesTokenButReportsHasToken() {
        insertUser("alice");
        McpConnectionService service = service();
        McpServerEntity created = service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "BEARER_TOKEN", "secret"));

        McpServerDto dto = service.toDto(service.require("alice", created.id()));

        assertThat(dto.hasToken()).isTrue();
        // The DTO type has no field that could carry the token — assert via serialization shape.
        assertThat(dto.toString()).doesNotContain("secret");
    }

    @Test
    void ownersAreIsolated() {
        insertUser("alice");
        insertUser("bob");
        McpConnectionService service = service();
        McpServerEntity aliceServer = service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null));

        assertThat(service.list("bob")).isEmpty();
        assertThatThrownBy(() -> service.require("bob", aliceServer.id()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateReplacesAndClearsToken() {
        insertUser("alice");
        McpConnectionService service = service();
        McpServerEntity created = service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "BEARER_TOKEN", "first"));

        McpServerEntity replaced = service.update("alice", created.id(),
                new McpUpdateRequest(null, null, null, "BEARER_TOKEN", "second", null));
        assertThat(encryption.decrypt(replaced.accessTokenEncrypted())).isEqualTo("second");

        McpServerEntity cleared = service.update("alice", created.id(),
                new McpUpdateRequest(null, null, null, "BEARER_TOKEN", null, true));
        assertThat(cleared.hasToken()).isFalse();
    }

    @Test
    void deleteCascadesToTools() {
        insertUser("alice");
        McpConnectionService service = service();
        McpServerEntity created = service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null));
        toolRepository.insert(newTool(created.id(), "create_issue", "mcp_linear_create_issue", true, false));

        service.delete("alice", created.id());

        assertThat(toolRepository.findByServer(created.id())).isEmpty();
        assertThat(serverRepository.findByIdAndOwner(created.id(), "alice")).isEmpty();
    }

    @Test
    void setToolEnabledIsOwnerScoped() {
        insertUser("alice");
        insertUser("bob");
        McpConnectionService service = service();
        McpServerEntity server = service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null));
        McpServerToolEntity tool = newTool(server.id(), "create_issue", "mcp_linear_create_issue", true, false);
        toolRepository.insert(tool);

        McpToolDto disabled = service.setToolEnabled("alice", server.id(), tool.id(), false);
        assertThat(disabled.enabled()).isFalse();

        // Bob cannot toggle Alice's tool.
        assertThatThrownBy(() -> service.setToolEnabled("bob", server.id(), tool.id(), true))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void duplicateNameRejected() {
        insertUser("alice");
        McpConnectionService service = service();
        service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null));

        assertThatThrownBy(() -> service.create("alice", new McpCreateRequest(
                "linear", "Linear 2", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void bearerTokenRequiredWhenAuthTypeBearer() {
        insertUser("alice");
        McpConnectionService service = service();
        assertThatThrownBy(() -> service.create("alice", new McpCreateRequest(
                "linear", "Linear", "STREAMABLE_HTTP", "https://example.com/mcp", "BEARER_TOKEN", "  ")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listReturnsOnlyOwnServers() {
        insertUser("alice");
        insertUser("bob");
        McpConnectionService service = service();
        service.create("alice", new McpCreateRequest(
                "a", "A", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null));
        service.create("bob", new McpCreateRequest(
                "b", "B", "STREAMABLE_HTTP", "https://example.com/mcp", "NONE", null));

        List<McpServerDto> aliceServers = service.listDtos("alice");
        assertThat(aliceServers).hasSize(1);
        assertThat(aliceServers.get(0).name()).isEqualTo("a");
    }
}
