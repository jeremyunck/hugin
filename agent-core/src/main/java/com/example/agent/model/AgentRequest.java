package com.example.agent.model;

/**
 * Request sent to the agent via the REST API.
 *
 * <p>{@code model} is optional; when blank the agent falls back to the configured default
 * ({@code llm.model}).
 *
 * <p>{@code sessionId} is optional; when present, short-term conversation memory recalls the recent
 * turns of that session and stores this exchange back. When blank, the request is stateless.
 *
 * <p>{@code apiKey} is optional; when present it overrides the default LLM provider's API key
 * (bring-your-own-key for the agent loop's LLM calls only — the embedding client still uses
 * the server-configured key).
 */
public record AgentRequest(String prompt, String model, String sessionId, String apiKey) {

    /** Stateless request with no session memory and no custom API key. */
    public AgentRequest(String prompt, String model) {
        this(prompt, model, null, null);
    }

    /** Request with session memory but no custom API key. */
    public AgentRequest(String prompt, String model, String sessionId) {
        this(prompt, model, sessionId, null);
    }
}
