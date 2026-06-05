package com.example.mcpclient.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvedTypeIsStdioWhenUrlIsNull() {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "server"), Map.of(), null, null);
        assertThat(def.resolvedType()).isEqualTo(McpServerDefinition.ServerType.STDIO);
    }

    @Test
    void resolvedTypeIsStdioWhenUrlIsBlank() {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "server"), Map.of(), "  ", null);
        assertThat(def.resolvedType()).isEqualTo(McpServerDefinition.ServerType.STDIO);
    }

    @Test
    void resolvedTypeIsStdioWhenUrlIsEmpty() {
        McpServerDefinition def = new McpServerDefinition("npx", List.of(), Map.of(), "", null);
        assertThat(def.resolvedType()).isEqualTo(McpServerDefinition.ServerType.STDIO);
    }

    @Test
    void resolvedTypeIsSseWhenUrlIsPresent() {
        McpServerDefinition def = new McpServerDefinition(null, null, null, "http://localhost:8080/sse", null);
        assertThat(def.resolvedType()).isEqualTo(McpServerDefinition.ServerType.SSE);
    }

    @Test
    void recordComponentsAreAccessible() {
        McpServerDefinition def = new McpServerDefinition(
                "npx",
                List.of("-y", "server"),
                Map.of("KEY", "value"),
                "http://example.com",
                Map.of("Authorization", "Bearer token")
        );
        assertThat(def.command()).isEqualTo("npx");
        assertThat(def.args()).containsExactly("-y", "server");
        assertThat(def.env()).containsEntry("KEY", "value");
        assertThat(def.url()).isEqualTo("http://example.com");
        assertThat(def.headers()).containsEntry("Authorization", "Bearer token");
    }

    @Test
    void jsonSerializationOmitsNullFields() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "server"), null, null, null);
        String json = objectMapper.writeValueAsString(def);
        assertThat(json).doesNotContain("url");
        assertThat(json).doesNotContain("headers");
        assertThat(json).doesNotContain("env");
        assertThat(json).contains("npx");
    }

    @Test
    void jsonSerializationIncludesAllNonNullFields() throws Exception {
        McpServerDefinition def = new McpServerDefinition(
                null, null, null, "http://localhost:9000/sse", Map.of("X-Key", "abc"));
        String json = objectMapper.writeValueAsString(def);
        assertThat(json).contains("http://localhost:9000/sse");
        assertThat(json).contains("X-Key");
        // null fields (command, args, env) should be omitted
        assertThat(json).doesNotContain("command");
        assertThat(json).doesNotContain("\"args\"");
    }

    @Test
    void serverTypeEnumValues() {
        assertThat(McpServerDefinition.ServerType.values())
                .containsExactlyInAnyOrder(McpServerDefinition.ServerType.STDIO, McpServerDefinition.ServerType.SSE);
    }
}
