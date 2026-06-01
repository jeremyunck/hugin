package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** OpenAI-compatible chat-completions request body. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        @JsonProperty("tool_choice") String toolChoice,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        ReasoningConfig reasoning,
        ThinkingConfig thinking,
        boolean stream
) {}
