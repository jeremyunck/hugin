package com.example.agent.tool;

import com.example.agent.model.ChatAttachment;

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
 * <p>{@code agentId} identifies the selected user agent when one exists. Tools that need to
 * scope memory or other side effects per agent should include it in their namespace.
 *
 * <p>{@code channelMessages} is an optional, client-supplied snapshot of the recent messages in the
 * caller's channel (oldest first). It is populated for front-ends like Discord that manage their own
 * short-term context, letting the {@code read_discord_channel} tool surface more history on demand.
 * It may be {@code null} when no such context was supplied.
 *
 * <p>{@code sandboxId} identifies the per-session sandbox (e.g. a Docker container) the request is
 * bound to. Tools that execute shell commands ({@code run_bash}) route them into this sandbox when it
 * is present; it may be {@code null} for requests that run against the default host workspace.
 *
 * <p>{@code attachments} carries any images the user attached to the message that triggered this
 * run. The default chat model is text-only and cannot view images, so the {@code describe_image}
 * tool reads them from here and forwards them to a vision-capable model. It may be {@code null} or
 * empty for requests with no attachments.
 */
public record ToolContext(
        Workspace workspace,
        String sessionId,
        String username,
        String agentId,
        List<String> channelMessages,
        String sandboxId,
        List<ChatAttachment> attachments) {

    /** Context without attachments (defaults {@code attachments} to {@code null}). */
    public ToolContext(
            Workspace workspace,
            String sessionId,
            String username,
            String agentId,
            List<String> channelMessages,
            String sandboxId) {
        this(workspace, sessionId, username, agentId, channelMessages, sandboxId, null);
    }

    /** Backwards-compatible context without a sandbox (defaults {@code sandboxId} to {@code null}). */
    public ToolContext(
            Workspace workspace,
            String sessionId,
            String username,
            String agentId,
            List<String> channelMessages) {
        this(workspace, sessionId, username, agentId, channelMessages, null, null);
    }

    /** Context with no session origin (stateless request). */
    public ToolContext(Workspace workspace) {
        this(workspace, null, null, null, null);
    }

    /** Context with a session origin but no client-supplied channel history. */
    public ToolContext(Workspace workspace, String sessionId) {
        this(workspace, sessionId, null, null, null);
    }

    /** Context with a session origin and authenticated owner but no channel history. */
    public ToolContext(Workspace workspace, String sessionId, String username) {
        this(workspace, sessionId, username, null, null);
    }

    /** Backwards-compatible context with a session origin and channel history but no username. */
    public ToolContext(Workspace workspace, String sessionId, List<String> channelMessages) {
        this(workspace, sessionId, null, null, channelMessages);
    }

    /** Context with authenticated owner and selected agent but no channel history. */
    public ToolContext(Workspace workspace, String sessionId, String username, String agentId) {
        this(workspace, sessionId, username, agentId, null);
    }
}
