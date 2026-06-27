package com.example.agent.tool;

import java.util.List;

/**
 * A provider of per-user tools resolved at request time from the authenticated owner.
 *
 * <p>Unlike {@link LocalToolRegistry} (process-wide built-ins) and {@code JustInTimeToolRegistry}
 * (workspace-local), these tools are owned by a specific user and must never be advertised to or
 * executed by anyone else. The MCP integration is the first implementation; the agent treats the
 * returned {@link LocalTool}s identically to any other tool, so the LLM cannot tell where a tool came
 * from.
 *
 * <p>Defined in {@code agent-core} (rather than the integration package) so {@code AgentService} can
 * depend on the abstraction without taking a dependency on any concrete integration. Additional
 * owner-scoped providers can be added later by implementing this interface.
 */
public interface OwnerScopedToolProvider {

    /** Tools the given owner may use right now (already filtered to enabled/available). */
    List<LocalTool> tools(String owner);

    /** Looks up an executable tool by advertised name for the owner, or {@code null} if none. */
    LocalTool find(String owner, String name);
}
