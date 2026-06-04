package com.example.mcpclient.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpPropertiesTest {

    @Test
    void nullConfigFileDefaultsToRelativePath() {
        McpProperties props = new McpProperties(null);
        assertThat(props.configFile()).isEqualTo("./mcp-servers.json");
    }

    @Test
    void blankConfigFileDefaultsToRelativePath() {
        McpProperties props = new McpProperties("   ");
        assertThat(props.configFile()).isEqualTo("./mcp-servers.json");
    }

    @Test
    void emptyConfigFileDefaultsToRelativePath() {
        McpProperties props = new McpProperties("");
        assertThat(props.configFile()).isEqualTo("./mcp-servers.json");
    }

    @Test
    void explicitConfigFileIsPreserved() {
        McpProperties props = new McpProperties("/etc/mcp/servers.json");
        assertThat(props.configFile()).isEqualTo("/etc/mcp/servers.json");
    }

    @Test
    void resolvedConfigFileExpandsHomeTilde() {
        McpProperties props = new McpProperties("~/.config/mcp-servers.json");
        String resolved = props.resolvedConfigFile();
        String home = System.getProperty("user.home");
        assertThat(resolved).startsWith(home);
        assertThat(resolved).endsWith("/.config/mcp-servers.json");
        assertThat(resolved).doesNotContain("~");
    }

    @Test
    void resolvedConfigFileReturnsAsIsForAbsolutePath() {
        McpProperties props = new McpProperties("/tmp/mcp-servers.json");
        assertThat(props.resolvedConfigFile()).isEqualTo("/tmp/mcp-servers.json");
    }

    @Test
    void resolvedConfigFileReturnsAsIsForRelativePath() {
        McpProperties props = new McpProperties("./mcp-servers.json");
        assertThat(props.resolvedConfigFile()).isEqualTo("./mcp-servers.json");
    }
}
