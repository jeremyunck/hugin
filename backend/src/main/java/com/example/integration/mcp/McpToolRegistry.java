package com.example.integration.mcp;

import com.example.agent.tool.LocalTool;
import com.example.agent.tool.OwnerScopedToolProvider;
import com.example.agent.tool.ToolContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owner-scoped registry that exposes a user's enabled MCP tools to the agent as {@link LocalTool}s.
 *
 * <p>This is the third tool provider alongside {@code LocalToolRegistry} and
 * {@code JustInTimeToolRegistry}. The agent treats the wrappers returned here exactly like any other
 * local tool — it neither knows nor cares that the tool is backed by a remote MCP server. Execution is
 * delegated to {@link McpToolInvoker}.
 *
 * <p>Isolation is enforced on every call: only tools that are enabled, non-stale, owned by the given
 * user, and attached to an enabled server are ever returned. A disabled tool, a tool on a disabled
 * server, and another user's tools are never advertised or executable.
 */
@Component
public class McpToolRegistry implements OwnerScopedToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final McpServerRepository serverRepository;
    private final McpServerToolRepository toolRepository;
    private final McpToolInvoker invoker;
    private final ObjectMapper objectMapper;

    public McpToolRegistry(McpServerRepository serverRepository,
                           McpServerToolRepository toolRepository,
                           McpToolInvoker invoker,
                           ObjectMapper objectMapper) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    /** All MCP tools the given user may use right now, as agent-ready {@link LocalTool} wrappers. */
    @Override
    public List<LocalTool> tools(String owner) {
        if (owner == null || owner.isBlank()) {
            return List.of();
        }
        List<LocalTool> result = new ArrayList<>();
        for (McpServerEntity server : serverRepository.findEnabledByOwner(owner)) {
            for (McpServerToolEntity tool : toolRepository.findByServer(server.id())) {
                if (tool.enabled() && !tool.stale()) {
                    result.add(new McpLocalTool(tool));
                }
            }
        }
        return result;
    }

    /**
     * Looks up an executable MCP tool by its advertised name for the given owner, or returns
     * {@code null} when the user has no such enabled tool (wrong owner, disabled, stale, or disabled
     * server). The returned wrapper delegates execution to {@link McpToolInvoker}.
     */
    @Override
    public LocalTool find(String owner, String huginToolName) {
        if (owner == null || owner.isBlank() || huginToolName == null) {
            return null;
        }
        return toolRepository.findByHuginToolName(huginToolName)
                .filter(tool -> tool.enabled() && !tool.stale())
                .filter(tool -> serverRepository.findByIdAndOwner(tool.serverId(), owner)
                        .map(McpServerEntity::enabled)
                        .orElse(false))
                .<LocalTool>map(McpLocalTool::new)
                .orElse(null);
    }

    /** Adapts a stored MCP tool to the agent's {@link LocalTool} contract. */
    private final class McpLocalTool implements LocalTool {
        private final McpServerToolEntity tool;

        private McpLocalTool(McpServerToolEntity tool) {
            this.tool = tool;
        }

        @Override
        public String name() {
            return tool.huginToolName();
        }

        @Override
        public String description() {
            String description = tool.description();
            return description == null || description.isBlank()
                    ? "MCP tool '" + tool.toolName() + "'."
                    : description;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return parseSchema(tool.huginToolName(), tool.inputSchemaJson());
        }

        @Override
        public String execute(Map<String, Object> arguments) {
            // MCP tools are not workspace-bound; the no-context path has no authenticated owner, so it
            // cannot resolve a user. Real execution always goes through execute(args, ctx).
            return "MCP tools require an authenticated request context.";
        }

        @Override
        public String execute(Map<String, Object> arguments, ToolContext ctx) {
            String owner = ctx == null ? null : ctx.username();
            if (owner == null || owner.isBlank()) {
                return "MCP tool '" + tool.huginToolName() + "' requires an authenticated user.";
            }
            return invoker.invoke(owner, tool.huginToolName(), arguments,
                    ctx.agentId(), ctx.sessionId());
        }
    }

    private Map<String, Object> parseSchema(String huginToolName, String json) {
        if (json == null || json.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of("type", "object", "properties", Map.of()) : parsed;
        } catch (Exception e) {
            log.warn("Could not parse stored input schema for MCP tool {}: {}", huginToolName, e.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
    }
}
