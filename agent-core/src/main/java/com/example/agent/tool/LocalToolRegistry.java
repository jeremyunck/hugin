package com.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the built-in {@link LocalTool}s available to the agent.
 *
 * <p>Collects every {@link LocalTool} bean and indexes it by name. When
 * {@code agent.tools.enabled} is false the registry is empty, so no local tools are
 * advertised or executable.
 */
@Component
public class LocalToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalToolRegistry.class);

    private final Map<String, LocalTool> byName = new LinkedHashMap<>();

    public LocalToolRegistry(List<LocalTool> tools, LocalToolProperties properties) {
        if (Boolean.TRUE.equals(properties.enabled())) {
            for (LocalTool tool : tools) {
                LocalTool previous = byName.put(tool.name(), tool);
                if (previous != null) {
                    log.warn("Duplicate local tool name '{}'; keeping {}", tool.name(),
                            tool.getClass().getSimpleName());
                }
            }
            log.info("Built-in local tools enabled: {}", byName.keySet());
        } else {
            log.info("Built-in local tools disabled (agent.tools.enabled=false)");
        }
    }

    /** All enabled local tools, in registration order. */
    public Collection<LocalTool> tools() {
        return byName.values();
    }

    /** Looks up a local tool by name, or returns {@code null} if none/disabled. */
    public LocalTool find(String name) {
        return byName.get(name);
    }
}
