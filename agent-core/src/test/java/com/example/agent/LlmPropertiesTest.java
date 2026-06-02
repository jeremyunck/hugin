package com.example.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LlmProperties} — constructor defaults, {@link LlmProperties#activeProvider()},
 * and {@link LlmProperties.Provider#hasApiKey()}.
 */
class LlmPropertiesTest {

    @Test
    void defaultsToOllamaProviderWhenProviderIsNull() {
        var props = new LlmProperties(null, "llama3.2", null,
                Map.of("ollama", new LlmProperties.Provider("http://localhost:11434/v1", null)));

        assertThat(props.provider()).isEqualTo("ollama");
        assertThat(props.reasoningEffort()).isEqualTo("medium");
        assertThat(props.activeProvider().baseUrl()).isEqualTo("http://localhost:11434/v1");
    }

    @Test
    void defaultsToOllamaProviderWhenProviderIsBlank() {
        var props = new LlmProperties("   ", "llama3.2", "   ",
                Map.of("ollama", new LlmProperties.Provider("http://localhost:11434/v1", null)));

        assertThat(props.provider()).isEqualTo("ollama");
        assertThat(props.reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void activeProviderReturnsCorrectProvider() {
        var openrouter = new LlmProperties.Provider("https://openrouter.ai/api/v1", "sk-or-abc");
        var ollama = new LlmProperties.Provider("http://localhost:11434/v1", null);
        var props = new LlmProperties("openrouter", "gpt-4o", "high",
                Map.of("openrouter", openrouter, "ollama", ollama));

        LlmProperties.Provider active = props.activeProvider();

        assertThat(active).isSameAs(openrouter);
        assertThat(active.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
    }

    @Test
    void throwsWhenActiveProviderNotConfigured() {
        var props = new LlmProperties("missing", "m", "medium",
                Map.of("ollama", new LlmProperties.Provider("http://localhost:11434/v1", null)));

        assertThatThrownBy(props::activeProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("llm.providers.missing");
    }

    @Test
    void throwsWhenProvidersMapIsEmptyAndActiveProviderLookedUp() {
        var props = new LlmProperties("ollama", "llama3.2", "medium", Map.of());

        assertThatThrownBy(props::activeProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ollama");
    }

    @Test
    void reasoningEffortDefaultsToMediumWhenBlank() {
        var props = new LlmProperties("ollama", "llama3.2", "   ",
                Map.of("ollama", new LlmProperties.Provider("http://localhost:11434/v1", null)));

        assertThat(props.reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void providerHasApiKeyReturnsTrueWhenKeyPresent() {
        var provider = new LlmProperties.Provider("https://openrouter.ai/api/v1", "sk-secret");

        assertThat(provider.hasApiKey()).isTrue();
    }

    @Test
    void providerHasApiKeyReturnsFalseWhenKeyIsNull() {
        var provider = new LlmProperties.Provider("http://localhost:11434/v1", null);

        assertThat(provider.hasApiKey()).isFalse();
    }

    @Test
    void providerHasApiKeyReturnsFalseWhenKeyIsBlank() {
        var provider = new LlmProperties.Provider("http://localhost:11434/v1", "   ");

        assertThat(provider.hasApiKey()).isFalse();
    }

    @Test
    void providerHasApiKeyReturnsFalseWhenKeyIsEmpty() {
        var provider = new LlmProperties.Provider("http://localhost:11434/v1", "");

        assertThat(provider.hasApiKey()).isFalse();
    }
}
