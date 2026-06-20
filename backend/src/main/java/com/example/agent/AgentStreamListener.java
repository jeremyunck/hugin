package com.example.agent;

/**
 * Callback used by {@link AgentService#chatStream} to deliver agent progress as it happens:
 * assistant text arrives token-by-token, and tool calls are reported as the loop runs them.
 *
 * <p>All methods default to no-ops so callers implement only the events they care about.
 */
public interface AgentStreamListener {

    /** A chunk of assistant text streamed from the model. */
    default void onContent(String delta) {}

    /** A chunk of assistant reasoning streamed from the model. */
    default void onReasoning(String delta) {}

    /** Emits one-time stream configuration before visible output begins. */
    default void onConfig(boolean developerMode) {}

    /** @deprecated implement the overload with {@code toolCallId} instead. */
    @Deprecated
    default void onToolCall(String toolName, String arguments) {}

    /** The model requested a specific tool call; about to be executed. */
    default void onToolCall(String toolCallId, String toolName, String arguments) {
        onToolCall(toolName, arguments);
    }

    /** @deprecated implement the overload with {@code toolCallId} instead. */
    @Deprecated
    default void onToolResult(String toolName, String result) {}

    /** A specific tool call finished; {@code result} is what will be fed back to the model. */
    default void onToolResult(String toolCallId, String toolName, String result) {
        onToolResult(toolName, result);
    }
}
