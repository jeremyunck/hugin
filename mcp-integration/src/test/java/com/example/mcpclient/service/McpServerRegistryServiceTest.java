package com.example.mcpclient.service;

import com.example.mcpclient.config.McpProperties;
import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.McpServersConfig;
import com.example.mcpclient.model.ServerInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link McpServerRegistryService}. No real MCP servers are started; a
 * nonexistent command is used so that connectServer fails fast (caught and stored internally).
 */
class McpServerRegistryServiceTest {

    @TempDir
    Path tmp;

    private static final String NONEXISTENT_CMD = "nonexistent-mcp-command-99999";
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // Connecting to NONEXISTENT_CMD never produces an init handshake, so the only thing bounding
    // each failed connection is the init timeout. Use a tiny one in tests so the suite fails fast
    // instead of blocking for the 120s production default on every connection attempt.
    private static final Duration TEST_INIT_TIMEOUT = Duration.ofMillis(250);

    private static McpProperties props(String configFile) {
        return new McpProperties(configFile, TEST_INIT_TIMEOUT);
    }

    private McpServerRegistryService serviceWithConfig(McpServersConfig config) throws Exception {
        Path configPath = tmp.resolve("mcp-servers.json");
        MAPPER.writeValue(configPath.toFile(), config);
        return new McpServerRegistryService(props(configPath.toString()));
    }

    private McpServerRegistryService serviceNoConfig() {
        return new McpServerRegistryService(
                props(tmp.resolve("missing.json").toString()));
    }

    private void awaitConnectionAttempt(McpServerRegistryService service, String name) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            ServerInfo info = service.getServer(name);
            if (info.connected() || info.error() != null) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Timed out waiting for MCP connection attempt for " + name);
    }

    @Test
    void initReturnsQuicklyEvenWhenServerConnectionIsSlow() throws Exception {
        var slow = new McpServerDefinition("/bin/sh", List.of("-lc", "sleep 2"), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("slow", slow)));

        long startNanos = System.nanoTime();
        service.init();
        long elapsedNanos = System.nanoTime() - startNanos;

        assertThat(elapsedNanos).isLessThan(Duration.ofMillis(200).toNanos());
        service.shutdown();
    }

    @Test
    void initWithMissingConfigStartsEmpty() throws Exception {
        var service = serviceNoConfig();
        service.init();
        assertThat(service.listServers()).isEmpty();
        service.shutdown();
    }

    @Test
    void initConnectsConfiguredServersAndRecordsFailures() throws Exception {
        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("srv1", def)));
        service.init();
        awaitConnectionAttempt(service, "srv1");

        List<ServerInfo> servers = service.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).name()).isEqualTo("srv1");
        assertThat(servers.get(0).connected()).isFalse();
        assertThat(servers.get(0).error()).isNotNull();
        service.shutdown();
    }

    @Test
    void shutdownWithNoClientsIsHarmless() throws Exception {
        var service = serviceNoConfig();
        service.init();
        service.shutdown();
    }

    @Test
    void listServersReturnsMultipleServers() throws Exception {
        var def1 = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        var def2 = new McpServerDefinition(NONEXISTENT_CMD, List.of("-v"), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("s1", def1, "s2", def2)));
        service.init();

        List<ServerInfo> servers = service.listServers();
        assertThat(servers).hasSize(2);
        assertThat(servers).extracting(ServerInfo::name).containsExactlyInAnyOrder("s1", "s2");
        service.shutdown();
    }

    @Test
    void getServerReturnsInfoForKnownServer() throws Exception {
        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("fs", def)));
        service.init();

        ServerInfo info = service.getServer("fs");
        assertThat(info.name()).isEqualTo("fs");
        assertThat(info.connected()).isFalse();
        service.shutdown();
    }

    @Test
    void getServerThrowsForUnknownServer() throws Exception {
        var service = serviceNoConfig();
        service.init();

        assertThatThrownBy(() -> service.getServer("no-such"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no-such");
        service.shutdown();
    }

    @Test
    void addServerPersistsConfigAndAttemptsConnection() throws Exception {
        var service = serviceNoConfig();
        service.init();

        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of("-x"), Map.of(), null, null);
        ServerInfo info = service.addServer("new-srv", def);

        assertThat(info.name()).isEqualTo("new-srv");
        assertThat(info.connected()).isFalse();
        assertThat(service.listServers()).hasSize(1);
        service.shutdown();
    }

    @Test
    void addServerWithNullCommandRecordsError() throws Exception {
        var service = serviceNoConfig();
        service.init();

        var def = new McpServerDefinition(null, List.of(), Map.of(), null, null);
        ServerInfo info = service.addServer("bad-stdio", def);
        assertThat(info.connected()).isFalse();
        assertThat(info.error()).isNotNull();
        service.shutdown();
    }

    @Test
    void removeServerDeletesAndDisconnects() throws Exception {
        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("srv", def)));
        service.init();

        service.removeServer("srv");

        assertThat(service.listServers()).isEmpty();
        assertThat(service.isConnected("srv")).isFalse();
        service.shutdown();
    }

    @Test
    void removeServerThrowsForUnknownServer() throws Exception {
        var service = serviceNoConfig();
        service.init();

        assertThatThrownBy(() -> service.removeServer("missing"))
                .isInstanceOf(NoSuchElementException.class);
        service.shutdown();
    }

    @Test
    void isConnectedReturnsFalseForFailedAndUnknown() throws Exception {
        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("srv", def)));
        service.init();

        assertThat(service.isConnected("srv")).isFalse();
        assertThat(service.isConnected("unknown")).isFalse();
        service.shutdown();
    }

    @Test
    void reconnectReAttemptsConnectionForKnownServer() throws Exception {
        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("srv", def)));
        service.init();

        ServerInfo info = service.reconnect("srv");
        assertThat(info.name()).isEqualTo("srv");
        assertThat(info.connected()).isFalse();
        service.shutdown();
    }

    @Test
    void reconnectThrowsForUnknownServer() throws Exception {
        var service = serviceNoConfig();
        service.init();

        assertThatThrownBy(() -> service.reconnect("missing"))
                .isInstanceOf(NoSuchElementException.class);
        service.shutdown();
    }

    @Test
    void connectTransientDoesNotPersistServer() throws Exception {
        var service = serviceNoConfig();
        service.init();

        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of(), Map.of(), null, null);
        service.connectTransient("transient", def);

        assertThat(service.isConnected("transient")).isFalse();
        assertThat(service.listServers()).isEmpty();
        service.shutdown();
    }

    @Test
    void callToolThrowsWhenServerNotConnected() throws Exception {
        var service = serviceNoConfig();
        service.init();

        assertThatThrownBy(() -> service.callTool("missing", "get_time", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not connected");
        service.shutdown();
    }

    @Test
    void listToolsThrowsWhenServerNotConnected() throws Exception {
        var service = serviceNoConfig();
        service.init();

        assertThatThrownBy(() -> service.listTools("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not connected");
        service.shutdown();
    }

    @Test
    void getAllToolsByServerReturnsEmptyWhenNoActiveClients() throws Exception {
        var service = serviceNoConfig();
        service.init();

        assertThat(service.getAllToolsByServer()).isEmpty();
        service.shutdown();
    }

    @Test
    void saveAndLoadConfigPreservesDefinition() throws Exception {
        var service = serviceNoConfig();
        service.init();

        var def = new McpServerDefinition(NONEXISTENT_CMD, List.of("--version"), Map.of("K", "V"), null, null);
        service.addServer("rt", def);

        List<ServerInfo> servers = service.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).definition().command()).isEqualTo(NONEXISTENT_CMD);
        assertThat(servers.get(0).definition().args()).containsExactly("--version");
        service.shutdown();
    }

    @Test
    void loadConfigThrowsOnMalformedJson() throws Exception {
        Path configPath = tmp.resolve("bad.json");
        java.nio.file.Files.writeString(configPath, "not-json-at-all");
        var service = new McpServerRegistryService(props(configPath.toString()));
        // init() calls loadConfig(), which will throw on malformed JSON
        assertThatThrownBy(service::init)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read MCP config");
        service.shutdown();
    }

    @Test
    void saveConfigThrowsWhenPathIsDirectory() throws Exception {
        // Create a directory at the config path location so ObjectMapper.writeValue fails
        Path dirPath = tmp.resolve("conflicting-dir");
        java.nio.file.Files.createDirectory(dirPath);
        // Point the service at a path inside that dir so init() starts clean,
        // then replace the target with a directory to provoke a write failure.
        Path nested = dirPath.resolve("servers.json");
        // Service with a missing file → init works fine
        var service = new McpServerRegistryService(props(nested.toString()));
        service.init();
        // Now create a directory at the json path to block writes
        java.nio.file.Files.createDirectory(nested);
        assertThatThrownBy(() -> service.saveConfig(McpServersConfig.empty()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to write MCP config");
        service.shutdown();
    }

    @Test
    void addServerOverwritesExistingEntry() throws Exception {
        var def1 = new McpServerDefinition(NONEXISTENT_CMD, List.of("-a"), Map.of(), null, null);
        var service = serviceWithConfig(new McpServersConfig(Map.of("srv", def1)));
        service.init();

        var def2 = new McpServerDefinition(NONEXISTENT_CMD, List.of("-b"), Map.of(), null, null);
        service.addServer("srv", def2);

        List<ServerInfo> servers = service.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).definition().args()).containsExactly("-b");
        service.shutdown();
    }
}
