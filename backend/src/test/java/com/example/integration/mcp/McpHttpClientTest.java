package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transport-level tests for {@link McpHttpClient} against a real local HTTP server. */
class McpHttpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    private McpHttpClient client(long requestTimeoutSeconds) {
        return new McpHttpClient(objectMapper, 5, requestTimeoutSeconds, 2_097_152);
    }

    private McpHttpClient.Connection connection() {
        return new McpHttpClient.Connection("http://localhost:" + server.getAddress().getPort() + "/mcp", "tok");
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", handler);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Replies to a JSON-RPC request based on its method; notifications (no id) get a 202. */
    private void rpcHandler(HttpExchange exchange) throws IOException {
        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        JsonNode id = request.get("id");
        if (id == null) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        String result = switch (method) {
            case "initialize" -> "{\"protocolVersion\":\"2025-06-18\","
                    + "\"serverInfo\":{\"name\":\"test-server\",\"version\":\"9.9\"},\"capabilities\":{}}";
            case "tools/list" -> "{\"tools\":[{\"name\":\"create_issue\",\"description\":\"Create\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}";
            case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}";
            default -> "{}";
        };
        writeJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        start(this::rpcHandler);
        JsonNode info = client(10).initialize(connection());
        assertThat(info.path("serverInfo").path("name").asText()).isEqualTo("test-server");
    }

    @Test
    void listToolsParsesTools() throws Exception {
        start(this::rpcHandler);
        List<McpHttpClient.DiscoveredTool> tools = client(10).listTools(connection());
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("create_issue");
    }

    @Test
    void callToolReturnsTextContent() throws Exception {
        start(this::rpcHandler);
        String result = client(10).callTool(connection(), "create_issue", Map.of("title", "x"));
        assertThat(result).isEqualTo("done");
    }

    @Test
    void httpErrorIsReportedReadably() throws Exception {
        start(exchange -> writeJson(exchange, 500, "internal error"));
        assertThatThrownBy(() -> client(10).initialize(connection()))
                .isInstanceOf(McpHttpClient.McpClientException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void invalidJsonIsReportedReadably() throws Exception {
        start(exchange -> writeJson(exchange, 200, "this is not json{{"));
        assertThatThrownBy(() -> client(10).initialize(connection()))
                .isInstanceOf(McpHttpClient.McpClientException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void timeoutIsReportedReadably() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, "{}");
        });
        assertThatThrownBy(() -> client(1).initialize(connection()))
                .isInstanceOf(McpHttpClient.McpClientException.class);
    }

    @Test
    void jsonRpcErrorIsSurfaced() throws Exception {
        start(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            JsonNode id = request.get("id");
            if (id == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
        });
        assertThatThrownBy(() -> client(10).initialize(connection()))
                .isInstanceOf(McpHttpClient.McpClientException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void sseResponseIsParsed() throws Exception {
        start(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            JsonNode id = request.get("id");
            if (id == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String data = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\","
                    + "\"serverInfo\":{\"name\":\"sse-server\"},\"capabilities\":{}}}";
            byte[] bytes = ("event: message\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        JsonNode info = client(10).initialize(connection());
        assertThat(info.path("serverInfo").path("name").asText()).isEqualTo("sse-server");
    }
}
