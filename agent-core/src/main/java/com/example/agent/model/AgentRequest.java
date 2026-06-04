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
 * <p>{@code recentMessages} is an optional, client-supplied snapshot of the recent messages in the
 * caller's channel (oldest first), used by front-ends like Discord that manage their own short-term
 * context. When non-null the server treats the caller as managing its own short-term memory: it does
 * <b>not</b> replay or record server-side conversation memory for the request, and the messages are
 * exposed to the agent through the {@code read_discord_channel} tool.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRequest(
        String prompt,
        String model,
        String decision,
        String complex,
        String simple,
        String sessionId,
        List<String> recentMessages) {

    /** Stateless request with no session memory. */
    public AgentRequest(String prompt, String model) {
        this(prompt, model, model, model, model, null, null);
    }

    /** Session-scoped request that uses server-side short-term conversation memory. */
    public AgentRequest(String prompt, String model, String sessionId) {
        this(prompt, model, model, model, model, sessionId, null);
    }

    /** Session-scoped request with client-managed recent message context. */
    public AgentRequest(String prompt, String model, String sessionId, List<String> recentMessages) {
        this(prompt, model, model, model, model, sessionId, recentMessages);
    }

    /** Routing-aware request that uses a decision model to select between simple and complex. */
    public AgentRequest(String prompt, String decision, String complex, String simple) {
        this(prompt, null, decision, complex, simple, null, null);
    }

    /** Routing-aware session request that uses a decision model to select between simple and complex. */
    public AgentRequest(String prompt, String decision, String complex, String simple,
                        String sessionId, List<String> recentMessages) {
        this(prompt, null, decision, complex, simple, sessionId, recentMessages);
    }
}
