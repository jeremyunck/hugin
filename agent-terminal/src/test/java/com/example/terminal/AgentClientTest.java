package com.example.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link AgentClient} parses the server's Server-Sent Events stream and dispatches
 * {@code token} / {@code tool} / {@code tool_result} / {@code done} events to the handler.
 */
class AgentClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesSseStreamIntoHandlerEvents() throws Exception {
        String sse = """
                event:token
                data:{"text":"Hi"}

                event:tool
                data:{"name":"get_time","args":"{}"}

                event:tool_result
                data:{"name":"get_time","result":"12:00"}

                event:token
                data:{"text":" there"}

                event:done
                data:{}

                """;
        AgentClient client = clientServing(sse);

        var tokens = new ArrayList<String>();
        var toolCalls = new ArrayList<String>();
        var toolResults = new ArrayList<String>();
        var errors = new ArrayList<String>();

        client.streamChat("hello", null, new AgentClient.Handler() {
            @Override public void onToken(String text) {
                tokens.add(text);
            }

            @Override public void onToolCall(String name, String args) {
                toolCalls.add(name + args);
            }

            @Override public void onToolResult(String name, String result) {
                toolResults.add(name + "=" + result);
            }

            @Override public void onError(String message) {
                errors.add(message);
            }
        });

        assertThat(tokens).containsExactly("Hi", " there");
        assertThat(toolCalls).containsExactly("get_time{}");
        assertThat(toolResults).containsExactly("get_time=12:00");
        assertThat(errors).isEmpty();
    }

    @Test
    void reportsServerErrorEvent() throws Exception {
        String sse = """
                event:error
                data:{"message":"boom"}

                """;
        AgentClient client = clientServing(sse);

        List<String> errors = new ArrayList<>();
        client.streamChat("x", null, new AgentClient.Handler() {
            @Override public void onToken(String text) {}

            @Override public void onError(String message) {
                errors.add(message);
            }
        });

        assertThat(errors).containsExactly("boom");
    }

    private AgentClient clientServing(String sseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/agent/stream", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        var properties = new TerminalProperties("http://localhost:" + port, null, null);
        return new AgentClient(properties, new ObjectMapper());
    }
}
