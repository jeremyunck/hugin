package com.example.discord;

import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the JDA bot lifecycle and routes incoming Discord messages to the agent server.
 *
 * <p>Responds to:
 * <ul>
 *   <li>Messages in guild channels whose ID appears in {@code discord.allowed-channel-ids}
 *   <li>Direct messages (when {@code discord.respond-to-dms} is true, the default)
 * </ul>
 *
 * <p>Session IDs are scoped per-channel (guild channels) or per-user (DMs) so the agent server's
 * short-term conversation memory is maintained across turns.
 *
 * <p><b>Required in the Discord Developer Portal (Applications → Bot → Privileged Gateway
 * Intents):</b> enable <b>Message Content Intent</b> or the bot will see empty message text.
 */
@Service
public class DiscordBotService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotService.class);
    private static final int DISCORD_MSG_LIMIT = 2000;

    private final DiscordProperties properties;
    private final DiscordAgentClient agentClient;
    private JDA jda;

    public DiscordBotService(DiscordProperties properties, DiscordAgentClient agentClient) {
        this.properties = properties;
        this.agentClient = agentClient;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        try {
            jda = JDABuilder.createDefault(properties.getBotToken())
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.DIRECT_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .addEventListeners(new MessageListener())
                    .build();
            jda.awaitReady();
            log.info("Discord bot connected as {}", jda.getSelfUser().getName());
            if (!properties.getAllowedChannelIds().isEmpty()) {
                log.info("Listening on {} guild channel(s)", properties.getAllowedChannelIds().size());
            }
            if (properties.isRespondToDms()) {
                log.info("DM responses enabled");
            }
        } catch (InvalidTokenException e) {
            throw new RuntimeException("Invalid Discord bot token — check discord.bot-token", e);
        }
    }

    @Override
    public void destroy() {
        if (jda != null) {
            log.info("Shutting down Discord bot");
            jda.shutdown();
        }
    }

    private class MessageListener extends ListenerAdapter {

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;

            boolean isDm = event.getChannelType() == ChannelType.PRIVATE;
            boolean isAllowedChannel = !isDm
                    && properties.getAllowedChannelIds().contains(event.getChannel().getId());

            if (!isDm && !isAllowedChannel) return;
            if (isDm && !properties.isRespondToDms()) return;

            // In guild channels, optionally gate on @mention
            if (!isDm && properties.isMentionOnly()) {
                boolean mentioned = event.getMessage().getMentions().getUsers().stream()
                        .anyMatch(u -> u.getIdLong() == jda.getSelfUser().getIdLong());
                if (!mentioned) return;
            }

            String content = event.getMessage().getContentStripped().strip();
            if (content.isBlank()) return;

            String sessionId = isDm
                    ? "discord-dm-" + event.getAuthor().getId()
                    : "discord-channel-" + event.getChannel().getId();

            event.getChannel().sendTyping().queue();

            Thread.ofVirtual().start(() -> handleMessage(event, content, sessionId));
        }
    }

    private void handleMessage(MessageReceivedEvent event, String content, String sessionId) {
        log.debug("Agent request — session={} author={}", sessionId, event.getAuthor().getName());
        StringBuilder response = new StringBuilder();
        try {
            agentClient.streamChat(content, sessionId, new DiscordAgentClient.Handler() {
                @Override
                public void onToken(String text) {
                    response.append(text);
                }

                @Override
                public void onError(String message) {
                    response.append("Agent error: ").append(message);
                }
            });
        } catch (Exception e) {
            log.error("Agent call failed for session {}", sessionId, e);
            event.getChannel().sendMessage("Sorry, I encountered an error. Please try again.").queue();
            return;
        }

        String text = response.toString().strip();
        if (text.isBlank()) text = "(no response)";

        List<String> chunks = splitMessage(text, DISCORD_MSG_LIMIT);
        for (int i = 0; i < chunks.size(); i++) {
            if (i == 0) {
                event.getMessage().reply(chunks.get(i)).queue();
            } else {
                event.getChannel().sendMessage(chunks.get(i)).queue();
            }
        }
    }

    /** Splits {@code text} on newlines first to avoid cutting mid-word, up to {@code limit} chars each. */
    static List<String> splitMessage(String text, int limit) {
        List<String> result = new ArrayList<>();
        while (text.length() > limit) {
            int split = text.lastIndexOf('\n', limit);
            if (split <= 0) split = limit;
            result.add(text.substring(0, split));
            text = text.substring(split).stripLeading();
        }
        if (!text.isEmpty()) result.add(text);
        return result;
    }
}
