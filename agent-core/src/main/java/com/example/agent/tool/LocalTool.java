package com.example.agent.tool;

import java.util.Map;

/**
 * A built-in tool executed locally by the agent (file access, search, shell, …).
 *
 * <p>Unlike tools sourced from MCP servers, these run in-process and have no MCP-SDK
 * dependency, so they live in {@code agent-core}. Implementations are discovered as
 * Spring beans and registered by {@link LocalToolRegistry}.
 */
public interface LocalTool {

    /** Tool name advertised to the model (must be unique among local tools). */
    String name();

    /** Human/model-readable description of what the tool does. */
    String description();

    /** JSON-schema {@code parameters} object describing the tool's arguments. */
    Map<String, Object> inputSchema();

    /**
     * Executes the tool and returns a plain-text result fed back to the model.
     * Thrown exceptions are caught by the agent loop and surfaced to the model.
     */
    String execute(Map<String, Object> arguments) throws Exception;

    /** Returns a required string argument, failing if absent or blank. */
    default String requiredString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: '" + key + "'");
        }
        return value.toString();
    }

    /** Returns a string argument that must be present (may be empty), failing only if absent. */
    default String presentString(Map<String, Object> args, String key) {
        if (!args.containsKey(key) || args.get(key) == null) {
            throw new IllegalArgumentException("Missing required argument: '" + key + "'");
        }
        return args.get(key).toString();
    }

    /** Returns a string argument, or {@code defaultValue} when absent/blank. */
    default String optionalString(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return (value == null || value.toString().isBlank()) ? defaultValue : value.toString();
    }

    /** Returns a boolean argument, or {@code defaultValue} when absent. */
    default boolean optionalBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /** Returns an int argument, or {@code defaultValue} when absent/unparseable. */
    default int optionalInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
