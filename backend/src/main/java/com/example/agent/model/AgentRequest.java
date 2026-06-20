package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request sent to the agent via the REST API.
 *
 * <p>{@code decision} is an LLM used only to classify request complexity. If the caller supplies
 * {@code decision}, {@code complex}, and {@code simple}, the agent asks {@code decision} to route
 * the request to either {@code complex} or {@code simple}. When those routing fields are omitted,
 * the agent falls back to the legacy single-model flow via {@code model}.
 *
 * <p>{@code model} is optional and remains a compatibility fallback for older callers. When blank,
 * the agent falls back to the configured default ({@code llm.model}).
 *
 * <p>{@code sessionId} is optional; when present, short-term conversation memory recalls the recent
 * turns of that session and stores this exchange back. When blank, the request is stateless.
 *
 * <p>{@code priorMessages} is an optional, client-supplied replay of the prior user/assistant
 * transcript for this chat. When present, those messages are appended before the current user turn
 * instead of loading short-term history from the server, but the server still records the new turn
 * unless {@code clientManagedContext} says otherwise.
 *
 * <p>{@code recentMessages} is an optional, client-supplied snapshot of the recent messages in the
 * caller's channel (oldest first), used by front-ends like Discord that manage their own short-term
 * context. They are exposed to the agent through the {@code read_discord_channel} tool.
 *
 * <p>{@code clientManagedContext} is an optional override for callers like Discord that fully own
 * their own short-term memory. When true, the server neither replays nor records short-term
 * conversation memory for the request.
 *
 * <p>{@code sandboxId} is optional; when present it selects the per-session sandbox (a Docker
 * container created via {@code POST /api/sandboxes}) the agent's tools run inside. The agent resolves
 * its workspace from this id and routes {@code run_bash} into that container. When blank the request
 * runs against the default (host) workspace, preserving the previous behaviour.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRequest(
        String prompt,
        List<ChatAttachment> attachments,
        String model,
        String reasoningEffort,
        String decision,
        String complex,
        String simple,
        String agentId,
        String systemPrompt,
        String sessionId,
        List<ChatMessage> priorMessages,
        List<String> recentMessages,
        String sandboxId,
        Boolean clientManagedContext) {

    /**
     * Backwards-compatible constructor without a sandbox (defaults {@code sandboxId} to {@code null}).
     * Preserves the previous 9-argument canonical signature for existing callers.
     */
    public AgentRequest(
            String prompt,
            List<ChatAttachment> attachments,
            String model,
            String decision,
            String complex,
            String simple,
            String agentId,
            String systemPrompt,
            String sessionId,
            List<String> recentMessages) {
        this(prompt, attachments, model, null, decision, complex, simple, agentId, systemPrompt, sessionId, null, recentMessages, null, null);
    }

    public AgentRequest(
            String prompt,
            List<ChatAttachment> attachments,
            String model,
            String reasoningEffort,
            String decision,
            String complex,
            String simple,
            String agentId,
            String systemPrompt,
            String sessionId,
            List<String> recentMessages) {
        this(prompt, attachments, model, reasoningEffort, decision, complex, simple, agentId, systemPrompt, sessionId, null, recentMessages, null, null);
    }

    /** Backwards-compatible constructor without attachments or a sandbox. */
    public AgentRequest(
            String prompt,
            String model,
            String decision,
            String complex,
            String simple,
            String agentId,
            String systemPrompt,
            String sessionId,
            List<String> recentMessages) {
        this(prompt, null, model, null, decision, complex, simple, agentId, systemPrompt, sessionId, null, recentMessages, null, null);
    }

    public AgentRequest(
            String prompt,
            String model,
            String reasoningEffort,
            String decision,
            String complex,
            String simple,
            String agentId,
            String systemPrompt,
            String sessionId,
            List<String> recentMessages) {
        this(prompt, null, model, reasoningEffort, decision, complex, simple, agentId, systemPrompt, sessionId, null, recentMessages, null, null);
    }

    /** Backwards-compatible constructor without attachments. */
    public AgentRequest(
            String prompt,
            String model,
            String decision,
            String complex,
            String simple,
            String agentId,
            String systemPrompt,
            String sessionId,
            List<String> recentMessages,
            String sandboxId) {
        this(prompt, null, model, null, decision, complex, simple, agentId, systemPrompt, sessionId, null, recentMessages, sandboxId, null);
    }

    public AgentRequest(
            String prompt,
            String model,
            String reasoningEffort,
            String decision,
            String complex,
            String simple,
            String agentId,
            String systemPrompt,
            String sessionId,
            List<String> recentMessages,
            String sandboxId) {
        this(prompt, null, model, reasoningEffort, decision, complex, simple, agentId, systemPrompt, sessionId, null, recentMessages, sandboxId, null);
    }

    /** Stateless request with no session memory. */
    public AgentRequest(String prompt, String model) {
        this(prompt, (List<ChatAttachment>) null, model, null, model, model, model, null, null, null, null, null, null, null);
    }

    /** Session-scoped request that uses server-side short-term conversation memory. */
    public AgentRequest(String prompt, String model, String sessionId) {
        this(prompt, (List<ChatAttachment>) null, model, null, model, model, model, null, null, sessionId, null, null, null, null);
    }

    /** Session-scoped request with client-managed recent message context. */
    public AgentRequest(String prompt, String model, String sessionId, List<String> recentMessages) {
        this(prompt, (List<ChatAttachment>) null, model, null, model, model, model, null, null, sessionId, null, recentMessages, null, null);
    }

    /** Routing-aware request that uses a decision model to select between simple and complex. */
    public AgentRequest(String prompt, String decision, String complex, String simple) {
        this(prompt, (List<ChatAttachment>) null, null, null, decision, complex, simple, null, null, null, null, null, null, null);
    }

    /** Routing-aware session request that uses a decision model to select between simple and complex. */
    public AgentRequest(String prompt, String decision, String complex, String simple,
                        String sessionId, List<String> recentMessages) {
        this(prompt, (List<ChatAttachment>) null, null, null, decision, complex, simple, null, null, sessionId, null, recentMessages, null, null);
    }

    /**
     * Single-model session request that replays a server-built transcript. The one {@code model}
     * both drives generation and stands in for the routing slots ({@code decision}/{@code complex}/
     * {@code simple} all collapse to it), matching the single-model convenience constructors above.
     * Used by the persisted chat session flow, which supplies its own {@code priorMessages} and owns
     * short-term context ({@code clientManagedContext}).
     */
    public AgentRequest(String prompt, List<ChatAttachment> attachments, String model, String reasoningEffort,
                        String sessionId, List<ChatMessage> priorMessages, String sandboxId,
                        Boolean clientManagedContext) {
        this(prompt, attachments, model, reasoningEffort, model, model, model, null, null, sessionId,
                priorMessages, null, sandboxId, clientManagedContext);
    }
}
