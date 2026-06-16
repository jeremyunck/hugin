package com.example.agent.model;

import java.util.List;

/**
 * Connection state of a single external integration, surfaced at {@code GET /api/integrations}.
 *
 * <p>Integrations are exposed to the agent as tools: each integration owns the {@code tools} it
 * provides, and those tools are only advertised to the model while {@code connected} is true. The UI
 * uses this to render the Integrations screen and to explain which capabilities are live.
 */
public record IntegrationStatus(
        String id,
        String name,
        String description,
        boolean connected,
        boolean reconnectable,
        String authMode,
        List<String> tools,
        String message) {
}
