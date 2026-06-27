package com.example.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests the stdio transport: the disabled/allow-list gates and a functional round-trip against a script. */
class McpStdioClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledClientRefusesToStart() {
        McpStdioClient client = new McpStdioClient(objectMapper, false, 10, "");
        assertThatThrownBy(() -> client.newSession("python3", List.of(), Map.of()))
                .isInstanceOf(McpHttpClient.McpClientException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void allowListBlocksUnlistedCommand() {
        McpStdioClient client = new McpStdioClient(objectMapper, true, 10, "node,uvx");
        assertThatThrownBy(() -> client.newSession("python3", List.of(), Map.of()))
                .isInstanceOf(McpHttpClient.McpClientException.class)
                .hasMessageContaining("not in");
    }

    @Test
    void roundTripsWithAStdioServerScript() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available");
        Path script = writeScript();
        McpStdioClient client = new McpStdioClient(objectMapper, true, 15, "");

        try (McpSession session = client.newSession("python3", List.of(script.toString()), Map.of())) {
            assertThat(session.serverInfo().path("serverInfo").path("name").asText()).isEqualTo("py-mcp");

            List<McpHttpClient.DiscoveredTool> tools = session.listTools();
            assertThat(tools).extracting(McpHttpClient.DiscoveredTool::name).containsExactly("echo");

            String result = session.callTool("echo", Map.of("value", "hi"));
            assertThat(result).contains("hi");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Path writeScript() throws Exception {
        String script = """
                import sys, json
                def send(o):
                    sys.stdout.write(json.dumps(o) + "\\n"); sys.stdout.flush()
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    msg = json.loads(line)
                    mid = msg.get("id")
                    method = msg.get("method")
                    if mid is None:
                        continue
                    if method == "initialize":
                        send({"jsonrpc":"2.0","id":mid,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"py-mcp","version":"1"},"capabilities":{}}})
                    elif method == "tools/list":
                        send({"jsonrpc":"2.0","id":mid,"result":{"tools":[{"name":"echo","description":"echo","inputSchema":{"type":"object"}}]}})
                    elif method == "tools/call":
                        args = msg.get("params", {}).get("arguments", {})
                        send({"jsonrpc":"2.0","id":mid,"result":{"content":[{"type":"text","text":"echo:" + json.dumps(args)}]}})
                    else:
                        send({"jsonrpc":"2.0","id":mid,"result":{}})
                """;
        Path path = Files.createTempFile("mcp-stdio-server", ".py");
        Files.writeString(path, script);
        return path;
    }
}
