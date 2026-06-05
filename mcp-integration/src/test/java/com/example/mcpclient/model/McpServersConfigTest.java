package com.example.mcpclient.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServersConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyReturnsConfigWithEmptyMap() {
        McpServersConfig config = McpServersConfig.empty();
        assertThat(config.mcpServers()).isNotNull().isEmpty();
    }

    @Test
    void nullMapIsReplacedWithEmptyMap() {
        McpServersConfig config = new McpServersConfig(null);
        assertThat(config.mcpServers()).isNotNull().isEmpty();
    }

    @Test
    void mcpServersAccessorReturnsProvidedMap() {
        Map<String, McpServerDefinition> servers = new LinkedHashMap<>();
        servers.put("fs", new McpServerDefinition("npx", List.of("-y", "fs"), null, null, null));
        McpServersConfig config = new McpServersConfig(servers);
        assertThat(config.mcpServers()).hasSize(1).containsKey("fs");
    }

    @Test
    void jsonDeserializationReadsMcpServersKey() throws Exception {
        String json = """
                {
                  "mcpServers": {
                    "time": {
                      "command": "uvx",
                      "args": ["mcp-server-time"]
                    }
                  }
                }
                """;
        McpServersConfig config = objectMapper.readValue(json, McpServersConfig.class);
        assertThat(config.mcpServers()).containsKey("time");
        assertThat(config.mcpServers().get("time").command()).isEqualTo("uvx");
    }

    @Test
    void jsonSerializationWritesMcpServersKey() throws Exception {
        McpServersConfig config = McpServersConfig.empty();
        String json = objectMapper.writeValueAsString(config);
        assertThat(json).contains("mcpServers");
    }

    @Test
    void roundTripSerializationPreservesData() throws Exception {
        Map<String, McpServerDefinition> servers = new LinkedHashMap<>();
        servers.put("fs", new McpServerDefinition("npx", List.of("-y", "fs-server"), null, null, null));
        McpServersConfig original = new McpServersConfig(servers);
        String json = objectMapper.writeValueAsString(original);
        McpServersConfig restored = objectMapper.readValue(json, McpServersConfig.class);
        assertThat(restored.mcpServers()).containsKey("fs");
        assertThat(restored.mcpServers().get("fs").command()).isEqualTo("npx");
        assertThat(restored.mcpServers().get("fs").args()).containsExactly("-y", "fs-server");
    }
}
