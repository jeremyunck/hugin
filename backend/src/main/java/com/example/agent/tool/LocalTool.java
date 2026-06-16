package com.example.agent.tool;

import java.util.Map;

/**
 * A built-in tool executed locally by the agent (file access, search, shell, …).
 *
 * <p>Unlike tools sourced from external services, these run in-process and have no transport
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
     * Whether this tool needs a real, writable workspace (filesystem or shell access) to be useful.
     *
     * <p>Tools that read, write, search, or execute against the workspace return {@code true}. The
     * agent uses this to keep filesystem/shell tools out of "pure chat" requests (those with no
     * sandbox bound to them), where there is no workspace the user expects the agent to touch, while
     * still advertising them for sandbox-backed sessions. Defaults to {@code false}.
     */
    default boolean requiresWorkspace() {
        return false;
    }

    /**
     * Whether this tool is currently available to be advertised to the model.
     *
     * <p>Integration-backed tools (web search, Google Workspace, …) override this to report whether
     * the integration is actually set up and enabled. When it returns {@code false} the agent does not
     * advertise the tool at all, so the model never sees a capability the user has not connected.
     * Defaults to {@code true} (always available).
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Executes the tool with the default (global) workspace.
     * Implementations call {@link #execute(Map, ToolContext)} internally; test stubs
     * may override only this method and the default {@code execute(args, ctx)} will delegate here.
     */
    String execute(Map<String, Object> arguments) throws Exception;

    /**
     * Executes the tool with the workspace resolved from {@code ctx}.
     * Built-in tool implementations override this to support per-agent workspaces; the default
     * delegates to {@link #execute(Map)} so that test stubs and legacy code continue to work.
     */
    default String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        return execute(arguments);
    }

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
