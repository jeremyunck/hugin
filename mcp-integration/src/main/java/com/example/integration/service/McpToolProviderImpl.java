package com.example.integration.service;

import com.example.agent.McpToolProvider;
import com.example.agent.model.AvailableTool;
import com.example.mcpclient.service.McpServerRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bridges {@link McpToolProvider} (agent-core interface) with
 * {@link McpServerRegistryService} (merged MCP registry implementation).
 *
 * <p>This is the only class that imports both MCP SDK types and agent-core types.
 */
@Service
public class McpToolProviderImpl implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProviderImpl.class);

    private final McpServerRegistryService registry;
    private final ObjectMapper objectMapper;

    public McpToolProviderImpl(McpServerRegistryService registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, List<AvailableTool>> getAllToolsByServer() {
        return registry.getAllToolsByServer().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(this::toAvailableTool)
                                .collect(Collectors.toList()),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new
                ));
    }

    @Override
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) {
        return registry.callTool(serverName, toolName, arguments);
    }

    private AvailableTool toAvailableTool(McpSchema.Tool tool) {
        Map<String, Object> schema;
        if (tool.inputSchema() != null) {
            try {
                schema = objectMapper.convertValue(tool.inputSchema(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Could not convert inputSchema for tool '{}': {}", tool.name(), e.getMessage());
                schema = Map.of("type", "object", "properties", Map.of());
            }
        } else {
            schema = Map.of("type", "object", "properties", Map.of());
        }
        return new AvailableTool(tool.name(), tool.description(), schema);
    }
}
