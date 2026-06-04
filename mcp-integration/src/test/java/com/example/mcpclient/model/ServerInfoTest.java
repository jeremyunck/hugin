package com.example.mcpclient.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerInfoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolInfoRecordFields() {
        ServerInfo.ToolInfo tool = new ServerInfo.ToolInfo("get_time", "Returns the current time");
        assertThat(tool.name()).isEqualTo("get_time");
        assertThat(tool.description()).isEqualTo("Returns the current time");
    }

    @Test
    void toolInfoWithNullDescription() {
        ServerInfo.ToolInfo tool = new ServerInfo.ToolInfo("tool_no_desc", null);
        assertThat(tool.name()).isEqualTo("tool_no_desc");
        assertThat(tool.description()).isNull();
    }

    @Test
    void serverInfoRecordFieldsAllPresent() {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "server"), null, null, null);
        List<ServerInfo.ToolInfo> tools = List.of(new ServerInfo.ToolInfo("read_file", "Reads a file"));
        ServerInfo info = new ServerInfo("fs", def, true, null, tools);

        assertThat(info.name()).isEqualTo("fs");
        assertThat(info.definition()).isEqualTo(def);
        assertThat(info.connected()).isTrue();
        assertThat(info.error()).isNull();
        assertThat(info.tools()).hasSize(1);
        assertThat(info.tools().get(0).name()).isEqualTo("read_file");
    }

    @Test
    void serverInfoRecordFieldsWithError() {
        McpServerDefinition def = new McpServerDefinition("npx", List.of(), null, null, null);
        ServerInfo info = new ServerInfo("broken", def, false, "Connection refused", null);

        assertThat(info.name()).isEqualTo("broken");
        assertThat(info.connected()).isFalse();
        assertThat(info.error()).isEqualTo("Connection refused");
        assertThat(info.tools()).isNull();
    }

    @Test
    void jsonSerializationOmitsNullError() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of(), null, null, null);
        ServerInfo info = new ServerInfo("fs", def, true, null, List.of());
        String json = objectMapper.writeValueAsString(info);
        assertThat(json).doesNotContain("\"error\"");
        assertThat(json).contains("\"name\"");
        assertThat(json).contains("\"connected\"");
    }

    @Test
    void jsonSerializationOmitsNullToolsOnToolInfo() throws Exception {
        ServerInfo.ToolInfo tool = new ServerInfo.ToolInfo("mytool", null);
        String json = objectMapper.writeValueAsString(tool);
        assertThat(json).doesNotContain("description");
        assertThat(json).contains("mytool");
    }

    @Test
    void jsonSerializationIncludesErrorWhenPresent() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of(), null, null, null);
        ServerInfo info = new ServerInfo("broken", def, false, "timeout", null);
        String json = objectMapper.writeValueAsString(info);
        assertThat(json).contains("timeout");
    }
}
