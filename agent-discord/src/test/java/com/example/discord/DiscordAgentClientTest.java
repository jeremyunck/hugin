package com.example.discord;

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

class DiscordAgentClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ignoresReasoningEventsButKeepsVisibleOutput() throws Exception {
        String sse = """
                event: reasoning
                data: {"text":"Thinking..."}

                event: token
                data: {"text":"Hello world"}

                event: done
                data: {}

                """;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/agent/stream", exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        DiscordProperties props = new DiscordProperties();
        props.setServerUrl("http://localhost:" + port);
        DiscordAgentClient client = new DiscordAgentClient(props, new ObjectMapper());

        List<String> tokens = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        client.streamChat("hello", "session-1", new DiscordAgentClient.Handler() {
            @Override public void onToken(String text) {
                tokens.add(text);
            }

            @Override public void onError(String message) {
                errors.add(message);
            }
        });

        assertThat(tokens).containsExactly("Hello world");
        assertThat(errors).isEmpty();
    }
}
