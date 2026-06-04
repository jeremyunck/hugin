package com.example.agent.tool;

import com.example.agent.ChatMessagePublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends a text message back to the caller's originating channel when a chat publisher is present.
 */
@Component
public class SendChatMessageTool implements LocalTool {

    private final Optional<ChatMessagePublisher> publisher;

    public SendChatMessageTool(Optional<ChatMessagePublisher> publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "send_chat_message";
    }

    @Override
    public String description() {
        return "Send a short message back to the originating chat target. The default target is "
                + "the current session id, which is how Discord/DM sessions route replies. Use this "
                + "to send a brief follow-up or notification.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "Message to send."),
                        "target", Map.of(
                                "type", "string",
                                "description", "Optional explicit delivery target. Defaults to the current session.")),
                "required", List.of("message"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return execute(arguments, new ToolContext(null));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        if (publisher.isEmpty()) {
            return "send_chat_message is unavailable: no ChatMessagePublisher is configured.";
        }
        String message = requiredString(arguments, "message");
        String target = optionalString(arguments, "target", ctx == null ? "" : ctx.sessionId());
        if (target.isBlank()) {
            return "send_chat_message is unavailable: no target session is available.";
        }
        Optional<String> delivered = publisher.get().send(target, message);
        return delivered.map(t -> "Sent message to " + t).orElse("Message delivery failed.");
    }
}
