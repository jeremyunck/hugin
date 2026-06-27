package com.example.integration.mcp;

import java.util.List;

/** Result of a tool-discovery run ({@code POST /servers/{id}/discover}). */
public record McpDiscoveryResponse(
        boolean success,
        String message,
        int discoveredCount,
        List<McpToolDto> tools) {

    public static McpDiscoveryResponse failure(String message) {
        return new McpDiscoveryResponse(false, message, 0, List.of());
    }
}
