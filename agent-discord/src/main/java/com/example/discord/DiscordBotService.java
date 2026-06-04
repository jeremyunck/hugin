package com.example.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import com.example.agent.ChatMessagePublisher;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
public class DiscordBotService implements DisposableBean, ChatMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotService.class);
    private static final int DISCORD_MSG_LIMIT = 2000;
    /** Recent channel messages injected into the prompt as Hugin's short-term context. */
    private static final int CHANNEL_CONTEXT_MESSAGES = 2;
    /** How many recent messages we fetch and forward; read_discord_channel can surface up to this many. */
    private static final int CHANNEL_HISTORY_FETCH = 10;
    private static final int DIAGNOSTIC_WORD_LIMIT = 200;
    private static final int DIAGNOSTIC_LOG_LINES = 120;
    private static final int DIAGNOSTIC_LOG_CHAR_LIMIT = 24_000;
    /**
     * Discord renders only a subset of Markdown. Tables are not supported, so we steer the model
     * away from them and post-process any that still appear.
     */
    private static final String DISCORD_FORMATTING_NOTE =
            "Discord does not render Markdown tables. Use headings, bullets, numbered lists, "
                    + "short paragraphs, and fenced code blocks instead.";
    /**
     * Per-session sliding window for the in-memory conversation and diagnostic logs. These grow for
     * the lifetime of a session, so without a cap a long-lived channel's bug report keeps growing
     * until it crosses GitHub's body limit and issue creation starts failing. Keep only the most
     * recent turns — older context is rarely useful in a bug report.
     */
    private static final int SESSION_LOG_MAX_ENTRIES = 60;
    /** GitHub rejects issue bodies longer than 65,536 characters; stay safely under it. */
    private static final int GITHUB_BODY_LIMIT = 65_000;

    private final DiscordProperties properties;
    private final DiscordAgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Path logDir;
    private JDA jda;

    /**
     * Per-session conversation logs. Each entry is a single turn: "User: ..." or "Agent: ...".
     * Guarded by ConcurrentHashMap and LinkedList is not thread-safe, but each session is only
     * accessed from one virtual thread at a time (serialised per-message within a session).
     */
    private final ConcurrentHashMap<String, LinkedList<String>> conversationLogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedList<String>> diagnosticLogs = new ConcurrentHashMap<>();
    // Cache the last known mode so the next stream starts with the current visibility.
    private volatile boolean lastKnownDeveloperMode = false;

    @Autowired
    public DiscordBotService(DiscordProperties properties, DiscordAgentClient agentClient,
                             ObjectMapper objectMapper) {
        this(properties, agentClient, objectMapper, Path.of(System.getProperty("user.home"), ".hugin", "logs"));
    }

    DiscordBotService(DiscordProperties properties, DiscordAgentClient agentClient,
                      ObjectMapper objectMapper, Path logDir) {
        this.properties = properties;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.logDir = logDir;
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

    // ---- Scheduled-result delivery ----

    /**
     * Posts the result of a scheduled prompt back to its origin. The {@code target} is the session id
     * captured when the prompt was scheduled — {@code discord-channel-<id>} or
     * {@code discord-dm-<userId>} for Discord origins; other targets are ignored here (they belong to
     * a different front-end). Called by {@link DeliverySubscriber} for each delivery event.
     */
    public void deliverScheduledResult(String target, String prompt, String result) {
        if (target == null || jda == null) {
            return;
        }
        String body = formatForDiscord((result == null || result.isBlank()) ? "(no result)" : result);
        String header = "⏰ **Scheduled result**"
                + (prompt == null || prompt.isBlank() ? "" : " for: " + oneLine(prompt));
        String message = header + "\n\n" + body;
        List<String> chunks = splitMessage(message, DISCORD_MSG_LIMIT);

        if (target.startsWith("discord-channel-")) {
            String channelId = target.substring("discord-channel-".length());
            var channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                log.warn("Cannot deliver scheduled result: unknown channel {}", channelId);
                return;
            }
            chunks.forEach(c -> channel.sendMessage(c).queue());
            logAgentResponse(target, body);
        } else if (target.startsWith("discord-dm-")) {
            String userId = target.substring("discord-dm-".length());
            jda.openPrivateChannelById(userId).queue(
                    channel -> {
                        chunks.forEach(c -> channel.sendMessage(c).queue());
                        logAgentResponse(target, body);
                    },
                    err -> log.warn("Cannot deliver scheduled result to user {}: {}",
                            userId, err.getMessage()));
        } else {
            log.debug("Ignoring scheduled delivery for non-Discord target '{}'", target);
        }
    }

    @Override
    public java.util.Optional<String> send(String target, String message) {
        if (target == null || target.isBlank() || jda == null) {
            return java.util.Optional.empty();
        }
        List<String> chunks = splitMessage(message == null ? "" : message, DISCORD_MSG_LIMIT);
        if (target.startsWith("discord-channel-")) {
            String channelId = target.substring("discord-channel-".length());
            var channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                return java.util.Optional.empty();
            }
            try {
                chunks.forEach(c -> channel.sendMessage(c).complete());
            } catch (Exception e) {
                log.warn("Cannot send Discord channel message to {}: {}", channelId, e.getMessage());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(target);
        }
        if (target.startsWith("discord-dm-")) {
            String userId = target.substring("discord-dm-".length());
            try {
                var channel = jda.openPrivateChannelById(userId).complete();
                chunks.forEach(c -> channel.sendMessage(c).complete());
            } catch (Exception e) {
                log.warn("Cannot send Discord DM to {}: {}", userId, e.getMessage());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(target);
        }
        var channel = jda.getTextChannelById(target);
        if (channel != null) {
            try {
                chunks.forEach(c -> channel.sendMessage(c).complete());
            } catch (Exception e) {
                log.warn("Cannot send Discord channel message to {}: {}", target, e.getMessage());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(target);
        }
        return java.util.Optional.empty();
    }

    // ---- Conversation log helpers ----

    private void logUserMessage(String sessionId, String author, String content) {
        addBounded(conversationLogs.computeIfAbsent(sessionId, k -> new LinkedList<>()),
                "[**" + author + "**] " + content);
    }

    private void logAgentResponse(String sessionId, String response) {
        LinkedList<String> log = conversationLogs.get(sessionId);
        if (log != null) {
            addBounded(log, "[**Hugin**] " + response);
        }
    }

    void logToolCall(String sessionId, String name, String args) {
        addBounded(diagnosticLogs.computeIfAbsent(sessionId, k -> new LinkedList<>()),
                "Tool call `" + name + "` args: " + truncateWords(oneLine(args), DIAGNOSTIC_WORD_LIMIT));
    }

    void logToolResult(String sessionId, String name, String result) {
        addBounded(diagnosticLogs.computeIfAbsent(sessionId, k -> new LinkedList<>()),
                "Tool result `" + name + "`: " + truncateWords(oneLine(result), DIAGNOSTIC_WORD_LIMIT));
    }

    void logAgentError(String sessionId, String message) {
        addBounded(diagnosticLogs.computeIfAbsent(sessionId, k -> new LinkedList<>()),
                "Agent error: " + truncateWords(oneLine(message), DIAGNOSTIC_WORD_LIMIT));
    }

    /** Appends {@code entry}, evicting the oldest entries so the log keeps only the most recent turns. */
    private static void addBounded(LinkedList<String> log, String entry) {
        log.add(entry);
        while (log.size() > SESSION_LOG_MAX_ENTRIES) {
            log.removeFirst();
        }
    }

    String buildIssueBody(String sessionId, String authorMention) {
        LinkedList<String> conversationLog = conversationLogs.get(sessionId);
        LinkedList<String> diagnosticLog = diagnosticLogs.get(sessionId);
        StringBuilder body = new StringBuilder();
        body.append("## Bug Report\n\n");
        body.append("**Session:** `").append(sessionId).append("`\n");
        body.append("**Reported by:** ").append(authorMention).append("\n");
        body.append("**Time:** ")
                .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
                        .format(Instant.now()))
                .append("\n\n");
        body.append("## Conversation Log\n\n");
        if (conversationLog == null || conversationLog.isEmpty()) {
            body.append("*(No conversation history available)*\n");
        } else {
            for (String entry : conversationLog) {
                body.append("- ").append(entry).append("\n");
            }
        }
        body.append("\n## Tool Calls\n\n");
        if (diagnosticLog == null || diagnosticLog.isEmpty()) {
            body.append("*(No tool calls or agent diagnostics recorded for this session)*\n");
        } else {
            for (String entry : diagnosticLog) {
                body.append("- ").append(entry).append("\n");
            }
        }
        appendLogExcerpt(body, "Hugin Server Log", logDir.resolve("hugin.log"));
        appendLogExcerpt(body, "Discord Bot Log", logDir.resolve("discord.log"));
        return capBody(body.toString());
    }

    /**
     * Caps an issue body to GitHub's character limit. Without this, a long-lived session's report
     * eventually exceeds the limit and GitHub rejects it with HTTP 422 — the "Report Bug" button
     * then appears to do nothing.
     */
    static String capBody(String body) {
        if (body == null || body.length() <= GITHUB_BODY_LIMIT) {
            return body;
        }
        String notice = "\n\n...(report truncated to fit GitHub's character limit)";
        return body.substring(0, GITHUB_BODY_LIMIT - notice.length()) + notice;
    }

    private void appendLogExcerpt(StringBuilder body, String title, Path logFile) {
        body.append("\n## ").append(title).append("\n\n");
        body.append("Last ").append(DIAGNOSTIC_LOG_LINES)
                .append(" lines; each line truncated to ")
                .append(DIAGNOSTIC_WORD_LIMIT).append(" words.\n\n");
        List<String> lines = tail(logFile, DIAGNOSTIC_LOG_LINES);
        if (lines.isEmpty()) {
            body.append("*(No readable log excerpt available from `")
                    .append(logFile).append("`)*\n");
            return;
        }
        body.append("```text\n");
        for (String line : boundedLogLines(lines, DIAGNOSTIC_LOG_CHAR_LIMIT)) {
            body.append(line).append("\n");
        }
        body.append("```\n");
    }

    static List<String> boundedLogLines(List<String> lines, int maxChars) {
        if (lines == null || lines.isEmpty() || maxChars <= 0) {
            return List.of();
        }
        LinkedList<String> bounded = new LinkedList<>();
        int totalChars = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = truncateWords(lines.get(i), DIAGNOSTIC_WORD_LIMIT);
            if (line.length() > maxChars) {
                line = line.substring(0, Math.max(0, maxChars - 15)) + "...(truncated)";
            }
            int nextChars = totalChars + line.length() + 1;
            if (nextChars > maxChars && !bounded.isEmpty()) {
                bounded.addFirst("...(older log lines omitted)");
                break;
            }
            bounded.addFirst(line);
            totalChars = nextChars;
        }
        return bounded;
    }

    static List<String> tail(Path path, int maxLines) {
        if (path == null || maxLines <= 0 || !Files.isReadable(path)) {
            return List.of();
        }
        ArrayDeque<String> buffer = new ArrayDeque<>(maxLines);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (buffer.size() == maxLines) {
                    buffer.removeFirst();
                }
                buffer.addLast(line);
            }
        } catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(buffer);
    }

    static String truncateWords(String text, int maxWords) {
        if (text == null || text.isBlank() || maxWords <= 0) {
            return "";
        }
        String[] words = text.strip().split("\\s+");
        if (words.length <= maxWords) {
            return text;
        }
        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) truncated.append(' ');
            truncated.append(words[i]);
        }
        return truncated.append(" ...(truncated)").toString();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
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
                "body", body,
                "labels", List.of("bug")
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

            // In guild channels, optionally only respond when Hugin is directly addressed — either
            // @mentioned or replied to. This is the default so Hugin stays quiet in busy channels.
            if (!isDm && properties.isMentionOnly()) {
                boolean mentioned = event.getMessage().getMentions().getUsers().stream()
                        .anyMatch(u -> u.getIdLong() == jda.getSelfUser().getIdLong());
                if (!mentioned && !isReplyToSelf(event)) return;
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

    /** True when {@code event} is a reply to one of Hugin's own messages. */
    private boolean isReplyToSelf(MessageReceivedEvent event) {
        var referenced = event.getMessage().getReferencedMessage();
        return referenced != null && jda != null
                && referenced.getAuthor().getIdLong() == jda.getSelfUser().getIdLong();
    }

    // ---- Button interaction listener ----

    private class ButtonListener extends ListenerAdapter {
        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String customId = event.getComponentId();
            if (!customId.startsWith("report_bug:")) return;

            // Disable the button immediately so it can't be clicked again
            event.editButton(Button.danger(customId, "Report Bug").asDisabled()).queue();

            String sessionId = customId.substring("report_bug:".length());
            String authorMention = event.getUser().getEffectiveName();
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
    }

    // ---- Message handling ----

    private void handleMessage(MessageReceivedEvent event, String content, String sessionId) {
        log.debug("Agent request — session={} author={}", sessionId, event.getAuthor().getName());
        // Short-term context comes from the channel itself: the messages immediately preceding the
        // one Hugin is replying to. We fetch up to CHANNEL_HISTORY_FETCH so the read_discord_channel
        // tool can surface more on demand, but only inject CHANNEL_CONTEXT_MESSAGES into the prompt.
        List<String> recentMessages = fetchRecentMessages(event, CHANNEL_HISTORY_FETCH);
        String prompt = buildPromptWithContext(event, content, recentMessages);
        StringBuilder response = new StringBuilder();
        AtomicBoolean developerMode = new AtomicBoolean(lastKnownDeveloperMode);
        try {
            agentClient.streamChat(prompt, sessionId, recentMessages, new DiscordAgentClient.Handler() {
                @Override
                public void onConfig(boolean enabled) {
                    lastKnownDeveloperMode = enabled;
                    developerMode.set(enabled);
                }

                @Override
                public void onToken(String text) {
                    response.append(text);
                }

                @Override
                public void onToolCall(String name, String args) {
                    logToolCall(sessionId, name, args);
                    if (!developerMode.get()) return;
                    event.getChannel().sendTyping().queue();
                    event.getChannel().sendMessage("Calling **" + name + "**...").queue(
                            null, err -> log.warn("Failed to post tool call to Discord: {}", err.getMessage()));
                }

                @Override
                public void onToolResult(String name, String result) {
                    String resultText = result == null ? "" : result;
                    logToolResult(sessionId, name, resultText);
                    if (!developerMode.get()) return;
                    String preview = resultText.length() > 200 ? resultText.substring(0, 200) + "..." : resultText;
                    event.getChannel().sendMessage("**" + name + "** result:\n```\n" + preview + "\n```").queue(
                            null, err -> log.warn("Failed to post tool result to Discord: {}", err.getMessage()));
                }

                @Override
                public void onError(String message) {
                    logAgentError(sessionId, message);
                    response.append("Agent error: ").append(message);
                }
            });
        } catch (Exception e) {
            log.error("Agent call failed for session {}", sessionId, e);
            logAgentError(sessionId, e.getMessage());
            // If tokens arrived before the error, send them rather than the generic fallback.
            if (response.length() == 0) {
                event.getChannel().sendMessage("Sorry, I encountered an error. Please try again.").queue();
                return;
            }
        }

        String text = response.toString().strip();
        if (text.isBlank()) text = "(no response)";

        logAgentResponse(sessionId, text);

        List<String> chunks = splitMessage(formatForDiscord(text), DISCORD_MSG_LIMIT);
        Button reportButton = Button.danger("report_bug:" + sessionId, "Report Bug");

        for (int i = 0; i < chunks.size(); i++) {
            if (i == 0) {
                event.getMessage().reply(chunks.get(i))
                        .addComponents(ActionRow.of(reportButton))
                        .queue();
            } else {
                event.getChannel().sendMessage(chunks.get(i)).queue();
            }
        }
    }

    /**
     * Prepends a compact Discord-context header to {@code content} so the model knows which
     * channel or DM it is responding in without needing to call any tools to discover it, followed
     * by the last {@link #CHANNEL_CONTEXT_MESSAGES} messages that preceded the one being replied to
     * as short-term context. (More history is available to the agent via {@code read_discord_channel}.)
     */
    private String buildPromptWithContext(MessageReceivedEvent event, String content,
                                          List<String> recentMessages) {
        StringBuilder ctx = new StringBuilder("[Discord context: ");
        boolean isDm = event.getChannelType() == ChannelType.PRIVATE;
        if (isDm) {
            ctx.append("direct message with ").append(event.getAuthor().getEffectiveName());
        } else {
            ctx.append("channel #").append(event.getChannel().getName())
               .append(" (ID ").append(event.getChannel().getId()).append(")");
            if (event.isFromGuild()) {
                ctx.append(", server: ").append(event.getGuild().getName());
            }
        }
        ctx.append("]\n");
        if (recentMessages != null && !recentMessages.isEmpty()) {
            int from = Math.max(0, recentMessages.size() - CHANNEL_CONTEXT_MESSAGES);
            ctx.append("[Recent channel messages (oldest first):");
            for (String message : recentMessages.subList(from, recentMessages.size())) {
                ctx.append("\n").append(message);
            }
            ctx.append("]\n");
        }
        ctx.append("[Discord formatting note: ").append(DISCORD_FORMATTING_NOTE).append("]\n");
        ctx.append(content);
        return ctx.toString();
    }

    /**
     * Fetches up to {@code limit} messages that immediately precede the triggering message, oldest
     * first, formatted as {@code "Author: text"}. Empty messages are skipped; Hugin's own prior
     * replies are kept so it has the back-and-forth context. Best-effort: returns an empty list if
     * history can't be read.
     */
    private List<String> fetchRecentMessages(MessageReceivedEvent event, int limit) {
        try {
            List<net.dv8tion.jda.api.entities.Message> history = event.getChannel()
                    .getHistoryBefore(event.getMessageId(), limit)
                    .complete()
                    .getRetrievedHistory();
            List<String> result = new ArrayList<>();
            // JDA returns history newest-first; walk it backwards to produce oldest-first.
            for (int i = history.size() - 1; i >= 0; i--) {
                net.dv8tion.jda.api.entities.Message message = history.get(i);
                String text = message.getContentStripped().strip();
                if (text.isBlank()) continue;
                result.add(message.getAuthor().getEffectiveName() + ": " + text);
            }
            return result;
        } catch (Exception e) {
            log.warn("Could not fetch channel history for {}: {}",
                    event.getChannel().getId(), e.getMessage());
            return List.of();
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

    /**
     * Discord's Markdown subset does not support tables, so convert GitHub-flavored table blocks
     * into bullet lists before sending the content to the client.
     */
    static String formatForDiscord(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean inFence = false;

        for (int i = 0; i < lines.length; ) {
            String line = lines[i];

            if (isFenceLine(line)) {
                inFence = !inFence;
                out.append(line);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
                i++;
                continue;
            }

            if (!inFence && isTableHeaderLine(line)
                    && i + 1 < lines.length && isTableSeparatorLine(lines[i + 1])) {
                int j = i + 2;
                List<String> rowLines = new ArrayList<>();
                while (j < lines.length && isTableRowLine(lines[j])) {
                    rowLines.add(lines[j]);
                    j++;
                }
                out.append(formatTableBlock(line, rowLines));
                if (j < lines.length) {
                    out.append('\n');
                }
                i = j;
                continue;
            }

            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
            i++;
        }

        return out.toString();
    }

    private static String formatTableBlock(String headerLine, List<String> rowLines) {
        List<String> headers = parseTableRow(headerLine);
        if (headers.isEmpty()) {
            return headerLine;
        }

        StringBuilder out = new StringBuilder();
        if (headers.size() == 1) {
            out.append("**").append(headers.get(0).strip()).append("**");
        } else {
            out.append("**").append(String.join(" / ", headers)).append("**");
        }

        for (String rowLine : rowLines) {
            List<String> cells = parseTableRow(rowLine);
            if (cells.isEmpty()) {
                continue;
            }
            out.append('\n').append("- **").append(cells.get(0).strip()).append("**");
            if (cells.size() > 1) {
                out.append(" — ").append(String.join(" — ", cells.subList(1, cells.size())));
            }
        }

        return out.toString();
    }

    private static List<String> parseTableRow(String line) {
        if (line == null) {
            return List.of();
        }
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }

        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\s*\\|\\s*")) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static boolean isFenceLine(String line) {
        String trimmed = line == null ? "" : line.strip();
        return trimmed.startsWith("```");
    }

    private static boolean isTableHeaderLine(String line) {
        String trimmed = line == null ? "" : line.strip();
        return trimmed.startsWith("|") && trimmed.contains("|") && !isTableSeparatorLine(trimmed);
    }

    private static boolean isTableSeparatorLine(String line) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        String normalized = trimmed;
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] parts = normalized.split("\\|");
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            String cell = part.strip();
            if (cell.isEmpty() || !cell.matches(":?-{3,}:?")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTableRowLine(String line) {
        String trimmed = line == null ? "" : line.strip();
        return trimmed.startsWith("|") && trimmed.contains("|") && !isTableSeparatorLine(trimmed);
    }
}
