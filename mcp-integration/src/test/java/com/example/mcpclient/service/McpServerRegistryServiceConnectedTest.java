package com.example.mcpclient.service;

import com.example.mcpclient.config.McpProperties;
import com.example.mcpclient.model.ServerInfo;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link McpServerRegistryService} paths that require an active (connected)
 * {@link McpSyncClient}. A mock client is injected via reflection so no real MCP
 * servers are needed.
 */
@ExtendWith(MockitoExtension.class)
class McpServerRegistryServiceConnectedTest {

    @TempDir
    Path tmp;

    @Mock
    McpSyncClient mockClient;

    McpServerRegistryService service;
    Path configFile;

    @BeforeEach
    void setUp() throws Exception {
        configFile = tmp.resolve("mcp-servers.json");
        service = new McpServerRegistryService(new McpProperties(configFile.toString()));
        service.init(); // no file → empty config, safe
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** Injects the mock client into the service's activeClients map via reflection. */
    private void injectClient(String name) throws Exception {
        Field f = McpServerRegistryService.class.getDeclaredField("activeClients");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, McpSyncClient> map = (Map<String, McpSyncClient>) f.get(service);
        map.put(name, mockClient);
    }

    // -------------------------------------------------------------------------
    // shutdown with active clients
    // -------------------------------------------------------------------------

    @Test
    void shutdownClosesActiveClients() throws Exception {
        injectClient("srv");
        assertThatNoException().isThrownBy(() -> service.shutdown());
        verify(mockClient).close();
    }

    @Test
    void shutdownSwallowsCloseException() throws Exception {
        injectClient("srv");
        doThrow(new RuntimeException("close failed")).when(mockClient).close();
        assertThatNoException().isThrownBy(() -> service.shutdown());
    }

    // -------------------------------------------------------------------------
    // isConnected with active client
    // -------------------------------------------------------------------------

    @Test
    void isConnectedReturnsTrueForActiveClient() throws Exception {
        injectClient("srv");
        assertThat(service.isConnected("srv")).isTrue();
    }

    // -------------------------------------------------------------------------
    // disconnectServer with active client
    // -------------------------------------------------------------------------

    @Test
    void removeServerClosesActiveClient() throws Exception {
        // Write config first so removeServer can find and delete it
        String json = """
                {"mcpServers":{"srv":{"command":"npx","args":["-y","srv"]}}}
                """;
        Files.writeString(configFile, json);
        injectClient("srv");

        service.removeServer("srv");

        verify(mockClient).close();
        assertThat(service.isConnected("srv")).isFalse();
    }

    // -------------------------------------------------------------------------
    // listTools with active client
    // -------------------------------------------------------------------------

    @Test
    void listToolsReturnsToolsFromActiveClient() throws Exception {
        var tool = new McpSchema.Tool("read_file", null, "Read a file", null, null, null, null);
        var listResult = new McpSchema.ListToolsResult(List.of(tool), null);
        when(mockClient.listTools()).thenReturn(listResult);
        injectClient("fs");

        List<ServerInfo.ToolInfo> tools = service.listTools("fs");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("read_file");
        assertThat(tools.get(0).description()).isEqualTo("Read a file");
    }

    @Test
    void listToolsReturnsEmptyListWhenNoTools() throws Exception {
        var listResult = new McpSchema.ListToolsResult(List.of(), null);
        when(mockClient.listTools()).thenReturn(listResult);
        injectClient("fs");

        List<ServerInfo.ToolInfo> tools = service.listTools("fs");
        assertThat(tools).isEmpty();
    }

    // -------------------------------------------------------------------------
    // buildServerInfo with connected client
    // -------------------------------------------------------------------------

    @Test
    void getServerBuildsInfoWithToolsWhenConnected() throws Exception {
        String json = """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","fs"]}}}
                """;
        Files.writeString(configFile, json);

        var tool = new McpSchema.Tool("write_file", null, "Write a file", null, null, null, null);
        var listResult = new McpSchema.ListToolsResult(List.of(tool), null);
        when(mockClient.listTools()).thenReturn(listResult);
        injectClient("fs");

        ServerInfo info = service.getServer("fs");
        assertThat(info.connected()).isTrue();
        assertThat(info.tools()).hasSize(1);
        assertThat(info.tools().get(0).name()).isEqualTo("write_file");
    }

    @Test
    void getServerSwallowsToolFetchException() throws Exception {
        String json = """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","fs"]}}}
                """;
        Files.writeString(configFile, json);

        when(mockClient.listTools()).thenThrow(new RuntimeException("list tools failed"));
        injectClient("fs");

        // Should not throw; tools will be null
        ServerInfo info = service.getServer("fs");
        assertThat(info.connected()).isTrue();
        assertThat(info.tools()).isNull();
    }

    // -------------------------------------------------------------------------
    // getAllToolsByServer with active clients
    // -------------------------------------------------------------------------

    @Test
    void getAllToolsByServerReturnsMappedTools() throws Exception {
        var tool = new McpSchema.Tool("get_time", null, "Get the time", null, null, null, null);
        var listResult = new McpSchema.ListToolsResult(List.of(tool), null);
        when(mockClient.listTools()).thenReturn(listResult);
        injectClient("time");

        Map<String, List<McpSchema.Tool>> allTools = service.getAllToolsByServer();
        assertThat(allTools).containsKey("time");
        assertThat(allTools.get("time")).hasSize(1);
        assertThat(allTools.get("time").get(0).name()).isEqualTo("get_time");
    }

    @Test
    void getAllToolsByServerSwallowsListException() throws Exception {
        when(mockClient.listTools()).thenThrow(new RuntimeException("network error"));
        injectClient("broken");

        Map<String, List<McpSchema.Tool>> allTools = service.getAllToolsByServer();
        // Exception swallowed; broken server has no entry
        assertThat(allTools).doesNotContainKey("broken");
    }

    // -------------------------------------------------------------------------
    // callTool with active client
    // -------------------------------------------------------------------------

    @Test
    void callToolReturnsTextContentFromClient() throws Exception {
        var textContent = new McpSchema.TextContent("hello world");
        var callResult = new McpSchema.CallToolResult(List.of(textContent), false);
        when(mockClient.callTool(any())).thenReturn(callResult);
        injectClient("fs");

        String result = service.callTool("fs", "read_file", Map.of("path", "/tmp/test.txt"));
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void callToolReturnsEmptyStringWhenResultIsNull() throws Exception {
        when(mockClient.callTool(any())).thenReturn(null);
        injectClient("fs");

        String result = service.callTool("fs", "read_file", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void callToolReturnsEmptyStringWhenContentIsEmpty() throws Exception {
        var callResult = new McpSchema.CallToolResult(List.of(), false);
        when(mockClient.callTool(any())).thenReturn(callResult);
        injectClient("fs");

        String result = service.callTool("fs", "read_file", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void callToolFiltersBlankTextContent() throws Exception {
        var blank = new McpSchema.TextContent("   ");
        var real = new McpSchema.TextContent("useful output");
        var callResult = new McpSchema.CallToolResult(List.of(blank, real), false);
        when(mockClient.callTool(any())).thenReturn(callResult);
        injectClient("fs");

        String result = service.callTool("fs", "some_tool", Map.of());
        assertThat(result).isEqualTo("useful output");
    }
}
