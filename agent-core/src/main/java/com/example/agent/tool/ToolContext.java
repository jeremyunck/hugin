package com.example.agent.tool;

import java.util.List;

/**
 * Carries per-request context passed to {@link LocalTool#execute(java.util.Map, ToolContext)}.
 * The workspace may be different for each cloud agent, so it is resolved at call time and
 * threaded through here rather than being held as a singleton by the tool.
 *
 * <p>{@code sessionId} identifies the origin of the request (e.g. {@code discord-channel-123}).
 * Tools that need to route something back to the caller — such as {@code schedule_prompt}
 * delivering a scheduled result — use it as the reply-to / delivery target. It may be {@code null}
 * for stateless requests.
 *
 * <p>{@code username} identifies the authenticated owner of the request when one exists. Tool
 * implementations can use it to scope memory or other user-specific side effects.
 *
 * <p>{@code channelMessages} is an optional, client-supplied snapshot of the recent messages in the
 * caller's channel (oldest first). It is populated for front-ends like Discord that manage their own
 * short-term context, letting the {@code read_discord_channel} tool surface more history on demand.
 * It may be {@code null} when no such context was supplied.
 */
public record ToolContext(Workspace workspace, String sessionId, String username, List<String> channelMessages) {

    /** Context with no session origin (stateless request). */
    public ToolContext(Workspace workspace) {
        this(workspace, null, null, null);
    }

    /** Context with a session origin but no client-supplied channel history. */
    public ToolContext(Workspace workspace, String sessionId) {
        this(workspace, sessionId, null, null);
    }

    /** Context with a session origin and authenticated owner but no channel history. */
    public ToolContext(Workspace workspace, String sessionId, String username) {
        this(workspace, sessionId, username, null);
    }

    /** Backwards-compatible context with a session origin and channel history but no username. */
    public ToolContext(Workspace workspace, String sessionId, List<String> channelMessages) {
        this(workspace, sessionId, null, channelMessages);
    }
}
