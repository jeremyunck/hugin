package com.example.mcpclient.service;

import com.example.mcpclient.config.McpProperties;
import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.McpServersConfig;
import com.example.mcpclient.model.ServerInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class McpServerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistryService.class);

    private static final McpSchema.Implementation CLIENT_INFO =
            new McpSchema.Implementation("spring-mcp-client", "1.0.0");

    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper mcpJsonMapper;

    private final Map<String, McpSyncClient> activeClients = new ConcurrentHashMap<>();
    private final Map<String, String> connectionErrors = new ConcurrentHashMap<>();

    public McpServerRegistryService(McpProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.mcpJsonMapper = McpJsonDefaults.getMapper();
    }

    @PostConstruct
    public void init() {
        McpServersConfig config = loadConfig();
        config.mcpServers().forEach((name, def) -> {
            log.debug("Connecting to MCP server: {}", name);
            connectServer(name, def);
        });
    }

    @PreDestroy
    public void shutdown() {
        activeClients.forEach((name, client) -> {
            try {
                client.close();
                log.debug("Closed MCP client for server: {}", name);
            } catch (Exception e) {
                log.warn("Error closing client for '{}': {}", name, e.getMessage());
            }
        });
        activeClients.clear();
    }

    public List<ServerInfo> listServers() {
        McpServersConfig config = loadConfig();
        return config.mcpServers().entrySet().stream()
                .map(e -> buildServerInfo(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public ServerInfo getServer(String name) {
        McpServersConfig config = loadConfig();
        McpServerDefinition def = config.mcpServers().get(name);
        if (def == null) throw new NoSuchElementException("Server not found: " + name);
        return buildServerInfo(name, def);
    }

    public ServerInfo addServer(String name, McpServerDefinition definition) {
        McpServersConfig config = loadConfig();
        Map<String, McpServerDefinition> updated = new LinkedHashMap<>(config.mcpServers());
        updated.put(name, definition);
        saveConfig(new McpServersConfig(updated));
        disconnectServer(name);
        connectServer(name, definition);
        return buildServerInfo(name, definition);
    }

    public void removeServer(String name) {
        McpServersConfig config = loadConfig();
        if (!config.mcpServers().containsKey(name))
            throw new NoSuchElementException("Server not found: " + name);
        Map<String, McpServerDefinition> updated = new LinkedHashMap<>(config.mcpServers());
        updated.remove(name);
        saveConfig(new McpServersConfig(updated));
        disconnectServer(name);
    }

    public boolean isConnected(String name) {
        return activeClients.containsKey(name);
    }

    /**
     * Connects a server without persisting it to mcp-servers.json.
     * Used for servers that are registered programmatically at runtime.
     */
    public void connectTransient(String name, McpServerDefinition definition) {
        disconnectServer(name);
        connectServer(name, definition);
    }

    public ServerInfo reconnect(String name) {
        McpServersConfig config = loadConfig();
        McpServerDefinition def = config.mcpServers().get(name);
        if (def == null) throw new NoSuchElementException("Server not found: " + name);
        disconnectServer(name);
        connectServer(name, def);
        return buildServerInfo(name, def);
    }

    public List<ServerInfo.ToolInfo> listTools(String name) {
        McpSyncClient client = activeClients.get(name);
        if (client == null)
            throw new IllegalStateException("Server '" + name + "' is not connected");
        return client.listTools().tools().stream()
                .map(t -> new ServerInfo.ToolInfo(t.name(), t.description()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Agent integration – used by McpToolProviderImpl
    // -------------------------------------------------------------------------

    /**
     * Returns every tool from every active server, grouped by server name.
     * Used by the agent to build its OpenAI-format tool list at request time.
     */
    public Map<String, List<McpSchema.Tool>> getAllToolsByServer() {
        Map<String, List<McpSchema.Tool>> result = new LinkedHashMap<>();
        activeClients.forEach((name, client) -> {
            try {
                result.put(name, client.listTools().tools());
            } catch (Exception e) {
                log.warn("Failed to list tools for '{}': {}", name, e.getMessage());
            }
        });
        return result;
    }

    /**
     * Executes a single MCP tool call and returns the text content of the result.
     *
     * @param serverName name of the connected MCP server
     * @param toolName   name of the tool to invoke
     * @param arguments  parsed JSON arguments from the model's tool-call request
     */
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpSyncClient client = activeClients.get(serverName);
        if (client == null)
            throw new IllegalStateException("Server '" + serverName + "' is not connected");

        log.debug("→ MCP tool call: server={} tool={} args={}", serverName, toolName, arguments);

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, arguments));

        if (result == null || result.content() == null || result.content().isEmpty()) {
            log.debug("← MCP tool result: server={} tool={} result=(empty)", serverName, toolName);
            return "";
        }

        String output = result.content().stream()
                .map(c -> {
                    if (c instanceof McpSchema.TextContent tc) {
                        return tc.text();
                    }
                    // Fallback for image / embedded-resource content
                    try {
                        return objectMapper.writeValueAsString(c);
                    } catch (Exception ex) {
                        return c.toString();
                    }
                })
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
        log.debug("← MCP tool result: server={} tool={} result={}", serverName, toolName, output);
        return output;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void connectServer(String name, McpServerDefinition def) {
        McpSyncClient client = null;
        try {
            client = buildClient(def);
            client.initialize();
            activeClients.put(name, client);
            connectionErrors.remove(name);
            log.info("Connected to MCP server '{}'", name);
        } catch (Exception e) {
            if (client != null) {
                try { client.close(); } catch (Exception ex) {
                    log.debug("Error closing failed client for '{}': {}", name, ex.getMessage());
                }
            }
            connectionErrors.put(name, e.getMessage());
            log.warn("Failed to connect to MCP server '{}': {}", name, e.getMessage());
        }
    }

    private void disconnectServer(String name) {
        McpSyncClient existing = activeClients.remove(name);
        if (existing != null) {
            try { existing.close(); } catch (Exception e) {
                log.warn("Error closing client for '{}': {}", name, e.getMessage());
            }
        }
        connectionErrors.remove(name);
    }

    private McpSyncClient buildClient(McpServerDefinition def) {
        return switch (def.resolvedType()) {
            case STDIO -> buildStdioClient(def);
            case SSE   -> buildSseClient(def);
        };
    }

    private McpSyncClient buildStdioClient(McpServerDefinition def) {
        if (def.command() == null || def.command().isBlank())
            throw new IllegalArgumentException("'command' is required for stdio servers");
        String command = resolveCommandPath(def.command());
        ServerParameters.Builder builder = ServerParameters.builder(command);
        if (def.args() != null && !def.args().isEmpty()) builder.args(def.args());
        if (def.env()  != null && !def.env().isEmpty())  builder.env(resolveEnvVars(def.env()));
        return McpClient.sync(new StdioClientTransport(builder.build(), mcpJsonMapper))
                .clientInfo(CLIENT_INFO)
                .initializationTimeout(properties.initTimeout())
                .build();
    }

    /**
     * Resolves ${VAR_NAME} / $VAR_NAME patterns in env values from the JVM's
     * environment, so config files can reference external env vars like
     * {@code "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}"}.
     */
    static Map<String, String> resolveEnvVars(Map<String, String> env) {
        return env.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> resolveEnvValue(e.getValue())));
    }

    private static String resolveEnvValue(String value) {
        if (value == null || value.isEmpty()) return value;
        // Match ${VAR_NAME} and $VAR_NAME (but not $$VAR)
        return Pattern.compile("\\$\\{([^}]+)}|\\$(\\w+)").matcher(value)
                .replaceAll(mr -> {
                    String varName = mr.group(1) != null ? mr.group(1) : mr.group(2);
                    String resolved = System.getenv(varName);
                    return resolved != null ? resolved : mr.group();
                });
    }

    private String resolveCommandPath(String command) {
        if (command.contains("/")) {
            return command;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return command;
        for (String dir : pathEnv.split(":")) {
            Path candidate = Path.of(dir).resolve(command);
            if (Files.isExecutable(candidate)) {
                log.debug("Resolved command '{}' to '{}'", command, candidate);
                return candidate.toString();
            }
        }
        return command;
    }

    private McpSyncClient buildSseClient(McpServerDefinition def) {
        if (def.url() == null || def.url().isBlank())
            throw new IllegalArgumentException("'url' is required for SSE servers");
        return McpClient.sync(HttpClientSseClientTransport.builder(def.url()).build())
                .clientInfo(CLIENT_INFO)
                .initializationTimeout(properties.initTimeout())
                .build();
    }

    private ServerInfo buildServerInfo(String name, McpServerDefinition def) {
        boolean connected = activeClients.containsKey(name);
        String error = connectionErrors.get(name);
        List<ServerInfo.ToolInfo> tools = null;
        if (connected) {
            try { tools = listTools(name); }
            catch (Exception e) { log.debug("Could not fetch tools for '{}': {}", name, e.getMessage()); }
        }
        return new ServerInfo(name, def, connected, error, tools);
    }

    // -------------------------------------------------------------------------
    // Config file I/O
    // -------------------------------------------------------------------------

    McpServersConfig loadConfig() {
        Path path = Path.of(properties.resolvedConfigFile());
        if (!Files.exists(path)) {
            log.info("Config file '{}' not found – starting with empty config", path);
            return McpServersConfig.empty();
        }
        try {
            return objectMapper.readValue(path.toFile(), McpServersConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read MCP config from " + path, e);
        }
    }

    void saveConfig(McpServersConfig config) {
        Path path = Path.of(properties.resolvedConfigFile());
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            objectMapper.writeValue(path.toFile(), config);
            log.debug("Saved MCP config to '{}'", path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write MCP config to " + path, e);
        }
    }
}
