package com.example.mcpclient.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root object of {@code mcp-servers.json}, matching the standard format used by
 * Claude Desktop and other MCP-compatible tools.
 */
public record McpServersConfig(
        @JsonProperty("mcpServers") Map<String, McpServerDefinition> mcpServers
) {
    public McpServersConfig {
        if (mcpServers == null) {
            mcpServers = new LinkedHashMap<>();
        }
    }

    public static McpServersConfig empty() {
        return new McpServersConfig(new LinkedHashMap<>());
    }
}
