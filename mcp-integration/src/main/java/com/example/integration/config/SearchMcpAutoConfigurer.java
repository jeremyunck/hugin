package com.example.integration.config;

import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.service.McpServerRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registers the web-search MCP server at startup based on {@code search.provider}.
 *
 * <p>Skips registration if a server named {@code web-search} was already loaded from
 * {@code mcp-servers.json}, so the JSON file always takes precedence.
 */
@Component
public class SearchMcpAutoConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SearchMcpAutoConfigurer.class);
    private static final String SERVER_NAME = "web-search";

    private final McpServerRegistryService registry;
    private final SearchProperties searchProperties;

    public SearchMcpAutoConfigurer(McpServerRegistryService registry, SearchProperties searchProperties) {
        this.registry = registry;
        this.searchProperties = searchProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerSearchServer() {
        if (registry.isConnected(SERVER_NAME)) {
            log.info("'{}' MCP server already registered via mcp-servers.json, skipping auto-configuration", SERVER_NAME);
            return;
        }

        McpServerDefinition def = switch (searchProperties.provider().toLowerCase()) {
            case "none" -> {
                log.info("Search MCP auto-configuration disabled (search.provider=none)");
                yield null;
            }
            case "openrouter" -> new McpServerDefinition(
                    "python3",
                    List.of(searchProperties.openrouterScript()),
                    Map.of(),
                    null, null
            );
            default -> new McpServerDefinition(
                    "uvx",
                    List.of("duckduckgo-mcp-server"),
                    Map.of(),
                    null, null
            );
        };

        if (def == null) return;

        log.info("Registering '{}' MCP server using provider '{}'", SERVER_NAME, searchProperties.provider());
        registry.connectTransient(SERVER_NAME, def);
    }
}
