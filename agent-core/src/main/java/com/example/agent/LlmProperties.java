package com.example.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration for the LLM backend.
 *
 * <p>{@code provider} selects which entry under {@code providers} is active. Each provider is an
 * OpenAI-schema chat-completions endpoint described by a base URL and an optional API key. When the
 * key is present it is sent as an {@code Authorization: Bearer} header; when absent (e.g. a local
 * Ollama instance) no auth header is sent.
 */
@ConfigurationProperties("llm")
public record LlmProperties(String provider, String model, String reasoningEffort,
                            Map<String, Provider> providers) {

    public LlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "ollama";
        }
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            reasoningEffort = "medium";
        }
        if (providers == null) {
            providers = Map.of();
        }
    }

    /** Resolves the configuration for the currently selected {@link #provider()}. */
    public Provider activeProvider() {
        Provider active = providers.get(provider);
        if (active == null) {
            throw new IllegalStateException(
                    "No LLM provider configured under 'llm.providers." + provider
                            + "'. Configured providers: " + providers.keySet());
        }
        return active;
    }

    /** A single OpenAI-compatible endpoint. */
    public record Provider(String baseUrl, String apiKey) {

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
