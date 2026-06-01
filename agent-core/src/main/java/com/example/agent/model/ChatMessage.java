package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single message in the OpenAI chat-completions conversation.
 *
 * <p>Use the static factories to construct well-formed messages:
 * {@code ChatMessage.user(...)}, {@code ChatMessage.tool(...)}, etc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("reasoning_content") String reasoningContent,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, null);
    }

    public static ChatMessage assistant(String content) {
        return assistant(content, null);
    }

    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return assistantWithToolCalls(toolCalls, null);
    }

    public static ChatMessage assistant(String content, String reasoningContent) {
        return new ChatMessage("assistant", content, reasoningContent, null, null);
    }

    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls, String reasoningContent) {
        return new ChatMessage("assistant", null, reasoningContent, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, null, toolCallId);
    }
}
