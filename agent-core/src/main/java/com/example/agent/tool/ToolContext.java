package com.example.agent.tool;

/**
 * Carries per-request context passed to {@link LocalTool#execute(java.util.Map, ToolContext)}.
 * The workspace may be different for each cloud agent, so it is resolved at call time and
 * threaded through here rather than being held as a singleton by the tool.
 */
public record ToolContext(Workspace workspace) {}
