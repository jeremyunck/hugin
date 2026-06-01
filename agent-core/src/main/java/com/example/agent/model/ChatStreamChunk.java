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
            // Reasoning models (e.g. gpt-oss, deepseek) stream their chain-of-thought here and
            // sometimes leave {@code content} empty entirely. Captured so the answer is not lost.
            // OpenRouter uses "reasoning"; some providers use "reasoning_content".
            String reasoning,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls
    ) {}

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
