package com.example.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Each response includes a "Report Bug" button. When clicked, the conversation log for that
 * session is posted as a GitHub issue.
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
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private JDA jda;

    /**
     * Per-session conversation logs. Each entry is a single turn: "User: ..." or "Agent: ...".
     * Guarded by ConcurrentHashMap and LinkedList is not thread-safe, but each session is only
     * accessed from one virtual thread at a time (serialised per-message within a session).
     */
    private final ConcurrentHashMap<String, LinkedList<String>> conversationLogs = new ConcurrentHashMap<>();

    public DiscordBotService(DiscordProperties properties, DiscordAgentClient agentClient,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
                    .addEventListeners(new MessageListener(), new ButtonListener())
                    .build();
            jda.awaitReady();
            log.info("Discord bot connected as {}", jda.getSelfUser().getName());
            jda.getGuilds().forEach(g -> {
                log.info("Guild: '{}' id={}", g.getName(), g.getId());
                g.getTextChannels().forEach(c -> log.info("  Channel: #{} id={}", c.getName(), c.getId()));
            });
            if (!properties.getAllowedChannelIds().isEmpty()) {
                log.info("Listening on {} guild channel(s)", properties.getAllowedChannelIds().size());
            }
            if (properties.isRespondToDms()) {
                log.info("DM responses enabled");
            }
            if (properties.getGithubToken() != null && properties.getGithubRepo() != null) {
                log.info("GitHub issue reporting enabled for {}", properties.getGithubRepo());
            } else {
                log.info("GitHub issue reporting disabled — set discord.github-token and discord.github-repo");
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

    // ---- Conversation log helpers ----

    private void logUserMessage(String sessionId, String author, String content) {
        conversationLogs.computeIfAbsent(sessionId, k -> new LinkedList<>())
                .add("[**" + author + "**] " + content);
    }

    private void logAgentResponse(String sessionId, String response) {
        LinkedList<String> log = conversationLogs.get(sessionId);
        if (log != null) {
            log.add("[**Hugin**] " + response);
        }
    }

    private String buildIssueBody(String sessionId, String authorMention) {
        LinkedList<String> log = conversationLogs.get(sessionId);
        StringBuilder body = new StringBuilder();
        body.append("## Bug Report\n\n");
        body.append("**Session:** `").append(sessionId).append("`\n");
        body.append("**Reported by:** ").append(authorMention).append("\n");
        body.append("**Time:** ")
                .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
                        .format(Instant.now()))
                .append("\n\n");
        body.append("## Conversation Log\n\n");
        if (log == null || log.isEmpty()) {
            body.append("*(No conversation history available)*\n");
        } else {
            for (String entry : log) {
                body.append("- ").append(entry).append("\n");
            }
        }
        return body.toString();
    }

    // ---- GitHub issue creation ----

    /**
     * Creates a GitHub issue and returns its HTML URL.
     *
     * @throws IllegalStateException if GitHub reporting is not configured
     * @throws IOException           if the API call fails or returns a non-2xx status
     */
    private String createGitHubIssue(String title, String body) throws IOException, InterruptedException {
        if (properties.getGithubToken() == null || properties.getGithubToken().isBlank()
                || properties.getGithubRepo() == null || properties.getGithubRepo().isBlank()) {
            throw new IllegalStateException(
                    "GitHub issue reporting is not configured — set discord.github-token and discord.github-repo");
        }

        String json = objectMapper.writeValueAsString(Map.of(
                "title", title,
                "body", body
        ));

        HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://api.github.com/repos/" + properties.getGithubRepo() + "/issues"))
                .header("Authorization", "Bearer " + properties.getGithubToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/vnd.github.v3+json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        log.info("GitHub issue created: {}", response.body());
        String url = objectMapper.readTree(response.body()).path("html_url").asText(null);
        if (url == null || url.isBlank()) {
            throw new IOException("GitHub API response did not include an issue URL: " + response.body());
        }
        return url;
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    // ---- Message listener ----

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

            logUserMessage(sessionId, event.getAuthor().getEffectiveName(), content);
            event.getChannel().sendTyping().queue();

            Thread.ofVirtual().start(() -> handleMessage(event, content, sessionId));
        }
    }

    // ---- Button interaction listener ----

    private class ButtonListener extends ListenerAdapter {
        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String customId = event.getComponentId();
            if (!customId.startsWith("report_bug:")) return;

            // Acknowledge the interaction immediately (within Discord's ~3s window) and disable
            // the button so it can't be clicked again. editButton() both responds to the
            // interaction and edits the source message.
            event.editButton(Button.danger(customId, "Report Bug").asDisabled()).queue();

            String sessionId = customId.substring("report_bug:".length());
            String authorMention = event.getUser().getEffectiveName();

            // Creating the GitHub issue makes a blocking HTTP call. Never run it on the JDA
            // event-dispatch thread — blocking that thread stalls the gateway and makes the
            // interaction report as failed. Offload to a virtual thread like MessageListener does.
            Thread.ofVirtual().start(() -> submitBugReport(event, sessionId, authorMention));
        }
    }

    private void submitBugReport(ButtonInteractionEvent event, String sessionId, String authorMention) {
        String issueBody = buildIssueBody(sessionId, authorMention);
        String title = "Bug Report — " + sessionId;
        try {
            String url = createGitHubIssue(title, issueBody);
            event.getChannel().sendMessage("🐛 Bug report created: " + url).queue();
        } catch (Exception e) {
            log.error("Failed to create GitHub issue for session {}", sessionId, e);
            String stackTrace = stackTraceOf(e);
            // Discord caps messages at 2000 chars; keep the trace inside a fenced block.
            String trace = stackTrace.length() > DISCORD_MSG_LIMIT - 200
                    ? stackTrace.substring(0, DISCORD_MSG_LIMIT - 200) + "\n...(truncated)"
                    : stackTrace;
            event.getChannel().sendMessage(
                    "❌ Failed to create bug report: " + e.getMessage()
                            + "\n```\n" + trace + "\n```").queue();
        }
    }

    // ---- Message handling ----

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
                public void onToolCall(String name, String args) {
                    event.getChannel().sendTyping().queue();
                    event.getChannel().sendMessage("Calling **" + name + "**...").queue();
                }

                @Override
                public void onToolResult(String name, String result) {
                    String preview = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                    event.getChannel().sendMessage("**" + name + "** result:\n```\n" + preview + "\n```").queue();
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

        logAgentResponse(sessionId, text);

        List<String> chunks = splitMessage(text, DISCORD_MSG_LIMIT);
        Button reportButton = Button.danger("report_bug:" + sessionId, "Report Bug");

        for (int i = 0; i < chunks.size(); i++) {
            if (i == 0) {
                event.getMessage().reply(chunks.get(i))
                        .addActionRow(reportButton)
                        .queue();
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
