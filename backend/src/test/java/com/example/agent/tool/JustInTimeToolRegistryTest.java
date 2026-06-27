package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JustInTimeToolRegistryTest {

    @TempDir
    Path tmp;

    private LocalToolProperties properties;
    private Workspace workspace;
    private JustInTimeToolRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 4_000,
                List.of());
        workspace = new Workspace(properties);
        registry = new JustInTimeToolRegistry(properties, new ObjectMapper());
    }

    @Test
    void loadsAndExecutesToolFromManifest() throws Exception {
        Files.createDirectories(tmp.resolve(".bouw/jit-tools"));
        Files.createDirectories(tmp.resolve("scripts"));
        Files.writeString(tmp.resolve("scripts/echo_args.py"), """
                import json
                import sys

                payload = json.load(sys.stdin)
                print(payload["message"])
                """);

        Files.writeString(tmp.resolve(".bouw/jit-tools/echo.tool.json"), """
                {
                  "name": "echo_manifest",
                  "description": "Echoes a message from stdin JSON.",
                  "command": "python3",
                  "args": ["scripts/echo_args.py"],
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "message": { "type": "string" }
                    },
                    "required": ["message"]
                  }
                }
                """);

        List<LocalTool> tools = registry.tools(workspace);
        assertThat(tools).hasSize(1);

        LocalTool tool = tools.get(0);
        assertThat(tool.name()).isEqualTo("echo_manifest");
        assertThat(tool.description()).contains("Echoes a message");
        assertThat(tool.inputSchema()).containsEntry("type", "object");

        String result = tool.execute(Map.of("message", "hello world"), new ToolContext(workspace));
        assertThat(result).contains("exit code: 0");
        assertThat(result).contains("hello world");
    }

    @Test
    void rescansWorkspaceEachTimeSoNewToolsAppearWithoutRestart() throws Exception {
        Files.createDirectories(tmp.resolve(".bouw/jit-tools"));

        assertThat(registry.tools(workspace)).isEmpty();

        Files.writeString(tmp.resolve(".bouw/jit-tools/adder.tool.json"), """
                {
                  "name": "adder",
                  "command": "python3",
                  "args": ["-c", "import json,sys; data=json.load(sys.stdin); print(int(data['a']) + int(data['b']))"],
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "a": { "type": "integer" },
                      "b": { "type": "integer" }
                    },
                    "required": ["a", "b"]
                  }
                }
                """);

        LocalTool tool = registry.find("adder", workspace);
        assertThat(tool).isNotNull();
        assertThat(tool.execute(Map.of("a", 2, "b", 3), new ToolContext(workspace)))
                .contains("5");
    }

    @Test
    void reloadsManifestWhenItChangesAndServesCacheWhenItDoesNot() throws Exception {
        Files.createDirectories(tmp.resolve(".bouw/jit-tools"));
        Path manifest = tmp.resolve(".bouw/jit-tools/greeter.tool.json");
        Files.writeString(manifest, """
                { "name": "greeter", "description": "first version", "command": "true" }
                """);

        assertThat(registry.find("greeter", workspace).description()).isEqualTo("first version");
        // Unchanged directory: the cached scan is reused.
        assertThat(registry.tools(workspace)).isSameAs(registry.tools(workspace));

        Files.writeString(manifest, """
                { "name": "greeter", "description": "second version, edited", "command": "true" }
                """);
        assertThat(registry.find("greeter", workspace).description()).isEqualTo("second version, edited");

        Files.delete(manifest);
        assertThat(registry.find("greeter", workspace)).isNull();
        assertThat(registry.tools(workspace)).isEmpty();
    }
}
