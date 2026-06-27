package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.integration.mcp.McpHttpClient.DiscoveredTool;
import com.example.integration.mcp.McpHttpClient.McpClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP transport that runs a server as a local child process, speaking newline-delimited JSON-RPC over
 * the process's stdin/stdout (the MCP "stdio" transport).
 *
 * <p>SECURITY: this spawns arbitrary local processes, so it is <strong>disabled by default</strong>
 * ({@code mcp.stdio.enabled=false}). It is intended for self-hosted / single-user deployments; do not
 * enable it on a shared multi-user host, where it would let any user run commands on the server. An
 * optional command allow-list ({@code mcp.stdio.allowed-commands}) further restricts what may run.
 *
 * <p>The session keeps the process alive and serializes requests on it (one in-flight call at a time),
 * matching responses to requests by JSON-RPC id and ignoring interleaved notifications/log lines.
 */
@Component
public class McpStdioClient {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long requestTimeoutSeconds;
    private final List<String> allowedCommands;

    public McpStdioClient(
            ObjectMapper objectMapper,
            @Value("${mcp.stdio.enabled:false}") boolean enabled,
            @Value("${mcp.stdio.request-timeout-seconds:30}") long requestTimeoutSeconds,
            @Value("${mcp.stdio.allowed-commands:}") String allowedCommands) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.allowedCommands = parseAllowList(allowedCommands);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static List<String> parseAllowList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Spawns the command and completes the {@code initialize} handshake, returning a live session.
     *
     * @throws McpClientException if stdio is disabled, the command is not allow-listed, or startup fails
     */
    public McpSession newSession(String command, List<String> args, Map<String, String> env)
            throws McpClientException {
        if (!enabled) {
            throw new McpClientException("The stdio transport is disabled on this server "
                    + "(set mcp.stdio.enabled=true to allow local MCP processes).");
        }
        if (command == null || command.isBlank()) {
            throw new McpClientException("stdio MCP server is missing a command.");
        }
        if (!allowedCommands.isEmpty() && !allowedCommands.contains(command)) {
            throw new McpClientException("Command '" + command + "' is not in mcp.stdio.allowed-commands.");
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (env != null) {
            builder.environment().putAll(env);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new McpClientException("Could not start MCP process '" + command + "': " + e.getMessage(), e);
        }

        StdioSession session = new StdioSession(process);
        try {
            session.initialize();
            return session;
        } catch (McpClientException e) {
            session.close();
            throw e;
        }
    }

    /** A live stdio session bound to one child process. */
    private final class StdioSession implements McpSession {
        private final Process process;
        private final BufferedWriter stdin;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Thread readerThread;
        private final AtomicLong requestIds = new AtomicLong(1);
        private volatile JsonNode serverInfo;

        private StdioSession(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::drainStdout, "mcp-stdio-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
            Thread errReader = new Thread(this::drainStderr, "mcp-stdio-stderr");
            errReader.setDaemon(true);
            errReader.start();
        }

        private void drainStdout() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.offer(line);
                    }
                }
            } catch (IOException ignored) {
                // Stream closed on process exit — nothing more to read.
            }
        }

        private void drainStderr() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[mcp-stdio stderr] {}", line);
                }
            } catch (IOException ignored) {
                // ignore
            }
        }

        private void initialize() throws McpClientException {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", McpHttpClient.PROTOCOL_VERSION);
            params.set("capabilities", objectMapper.createObjectNode());
            ObjectNode clientInfo = objectMapper.createObjectNode();
            clientInfo.put("name", "bouw");
            clientInfo.put("version", "1.0");
            params.set("clientInfo", clientInfo);
            this.serverInfo = request("initialize", params);
            sendNotification();
        }

        private void sendNotification() {
            try {
                ObjectNode envelope = objectMapper.createObjectNode();
                envelope.put("jsonrpc", "2.0");
                envelope.put("method", "notifications/initialized");
                envelope.set("params", objectMapper.createObjectNode());
                writeLine(envelope);
            } catch (McpClientException e) {
                log.debug("MCP stdio initialized notification failed (ignored): {}", e.getMessage());
            }
        }

        @Override
        public JsonNode serverInfo() {
            return serverInfo;
        }

        @Override
        public synchronized List<DiscoveredTool> listTools() throws McpClientException {
            JsonNode result = request("tools/list", objectMapper.createObjectNode());
            JsonNode tools = result.path("tools");
            if (!tools.isArray()) {
                throw new McpClientException("stdio server returned a malformed tools/list response.");
            }
            List<DiscoveredTool> discovered = new ArrayList<>();
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                JsonNode schema = tool.has("inputSchema") ? tool.get("inputSchema") : null;
                discovered.add(new DiscoveredTool(name, tool.path("description").asText(""), schema));
            }
            return discovered;
        }

        @Override
        public synchronized String callTool(String name, Map<String, Object> arguments) throws McpClientException {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", name);
            params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));
            JsonNode result = request("tools/call", params);
            String text = McpResults.extractTextContent(result);
            if (result.path("isError").asBoolean(false)) {
                return "The MCP tool reported an error: " + (text.isBlank() ? "(no detail provided)" : text);
            }
            return text;
        }

        /** Writes a request line and reads response lines until the one matching our id arrives. */
        private JsonNode request(String method, JsonNode params) throws McpClientException {
            if (!process.isAlive()) {
                throw new McpClientException("MCP process has exited.");
            }
            long id = requestIds.getAndIncrement();
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", id);
            envelope.put("method", method);
            envelope.set("params", params);
            writeLine(envelope);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(requestTimeoutSeconds);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new McpClientException("Timed out waiting for MCP stdio response to " + method + ".");
                }
                String line;
                try {
                    line = lines.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new McpClientException("Interrupted waiting for MCP stdio response.");
                }
                if (line == null) {
                    throw new McpClientException("Timed out waiting for MCP stdio response to " + method + ".");
                }
                JsonNode message;
                try {
                    message = objectMapper.readTree(line);
                } catch (IOException e) {
                    // Not JSON (stray log line on stdout) — skip it.
                    continue;
                }
                if (!message.has("id") || message.get("id").isNull()) {
                    continue; // a notification — ignore
                }
                if (message.path("id").asLong(-1) != id) {
                    continue; // response to a different request (shouldn't happen while serialized)
                }
                if (message.has("error") && !message.get("error").isNull()) {
                    JsonNode error = message.get("error");
                    throw new McpClientException("MCP server error (" + error.path("code").asInt(0) + "): "
                            + error.path("message").asText("unknown error"));
                }
                JsonNode result = message.path("result");
                if (result.isMissingNode() || result.isNull()) {
                    throw new McpClientException("MCP stdio response for " + method + " had no result.");
                }
                return result;
            }
        }

        private void writeLine(JsonNode envelope) throws McpClientException {
            try {
                synchronized (stdin) {
                    stdin.write(objectMapper.writeValueAsString(envelope));
                    stdin.write("\n");
                    stdin.flush();
                }
            } catch (IOException e) {
                throw new McpClientException("Failed writing to MCP process: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // ignore
            }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            readerThread.interrupt();
        }
    }
}
