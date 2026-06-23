package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single Server-Sent Event chunk from an OpenAI-schema streaming chat-completions response
 * ({@code stream: true}). Each chunk carries incremental {@link Delta}s that are accumulated
 * into a final assistant message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatStreamChunk(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            // OpenRouter (the default provider) streams chain-of-thought under `reasoning` rather
            // than the DeepSeek-style `reasoning_content`. Capture both so reasoning models surface
            // their thinking regardless of which field the upstream provider uses.
            @JsonProperty("reasoning") String reasoning,
            @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls
    ) {
        /** The reasoning fragment for this delta, preferring the explicit field that is populated. */
        public String reasoningText() {
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                return reasoningContent;
            }
            return reasoning;
        }
    }

    /**
     * A partial tool call. {@code id}/{@code name} typically arrive in the first chunk for a given
     * {@code index}; {@code arguments} are streamed across subsequent chunks and concatenated.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDelta(
            int index,
            String id,
            String type,
            FunctionDelta function
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDelta(String name, String arguments) {}
}
