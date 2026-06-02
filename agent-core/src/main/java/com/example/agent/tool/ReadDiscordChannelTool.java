package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Reads recent messages from the Discord channel (or DM) that the current conversation originated
 * from. The Discord front-end keeps only the last couple of messages in the model's short-term
 * context to stay lean; this tool lets the agent pull back further history (up to the last
 * {@value #MAX_MESSAGES} messages) on demand when it needs more of the conversation to answer.
 *
 * <p>The history is supplied by the caller (the Discord bot) on the request and threaded through
 * {@link ToolContext#channelMessages()}. For non-Discord callers no history is present and the tool
 * reports that.
 */
@Component
public class ReadDiscordChannelTool implements LocalTool {

    private static final int MAX_MESSAGES = 10;

    @Override
    public String name() {
        return "read_discord_channel";
    }

    @Override
    public String description() {
        return "Read up to the last " + MAX_MESSAGES + " messages from the Discord channel that this "
                + "conversation came from, oldest first. Only the two most recent messages are kept in "
                + "your short-term context by default, so use this when you need more of the recent "
                + "channel conversation to answer. Only works for conversations that originated in "
                + "Discord.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "count", Map.of(
                                "type", "integer",
                                "description", "How many of the most recent messages to return "
                                        + "(1-" + MAX_MESSAGES + ", default " + MAX_MESSAGES + ").")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return execute(arguments, new ToolContext(null));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        List<String> messages = ctx == null ? null : ctx.channelMessages();
        if (messages == null || messages.isEmpty()) {
            return "No Discord channel history is available for this conversation "
                    + "(it did not originate from a Discord channel, or the channel has no earlier messages).";
        }

        int count = optionalInt(arguments, "count", MAX_MESSAGES);
        if (count <= 0) {
            count = MAX_MESSAGES;
        }
        count = Math.min(count, MAX_MESSAGES);

        int from = Math.max(0, messages.size() - count);
        List<String> slice = messages.subList(from, messages.size());

        StringBuilder sb = new StringBuilder("Last ")
                .append(slice.size())
                .append(slice.size() == 1 ? " message" : " messages")
                .append(" in the Discord channel (oldest first):");
        for (String message : slice) {
            sb.append('\n').append(message);
        }
        return sb.toString();
    }
}
