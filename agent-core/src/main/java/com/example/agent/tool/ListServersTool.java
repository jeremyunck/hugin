package com.example.agent.tool;

import com.example.agent.McpToolProvider;
import com.example.agent.model.AvailableTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Lists connected MCP servers and the tools each one exposes. */
@Component
public class ListServersTool implements LocalTool {

    private final McpToolProvider toolProvider;

    public ListServersTool(McpToolProvider toolProvider) {
        this.toolProvider = toolProvider;
    }

    @Override
    public String name() {
        return "list_mcp_servers";
    }

    @Override
    public String description() {
        return "List all connected MCP servers and the tools each server exposes. "
                + "Optionally filter to a single server by name.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "server", Map.of(
                                "type", "string",
                                "description", "Filter to a specific server name. Omit to list all servers.")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String filter = optionalString(arguments, "server", null);
        Map<String, List<AvailableTool>> byServer = toolProvider.getAllToolsByServer();

        if (byServer.isEmpty()) {
            return "No MCP servers are currently connected.";
        }

        if (filter != null && !byServer.containsKey(filter)) {
            return "No connected MCP server named '" + filter + "'. Connected servers: "
                    + String.join(", ", byServer.keySet());
        }

        StringBuilder sb = new StringBuilder();
        byServer.entrySet().stream()
                .filter(e -> filter == null || e.getKey().equals(filter))
                .forEach(e -> {
                    sb.append("Server: ").append(e.getKey()).append("\n");
                    List<AvailableTool> tools = e.getValue();
                    if (tools.isEmpty()) {
                        sb.append("  (no tools)\n");
                    } else {
                        for (AvailableTool tool : tools) {
                            sb.append("  - ").append(tool.name());
                            if (tool.description() != null && !tool.description().isBlank()) {
                                sb.append(": ").append(tool.description());
                            }
                            sb.append("\n");
                        }
                    }
                });
        return sb.toString().stripTrailing();
    }
}
