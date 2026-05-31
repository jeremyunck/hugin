package com.example.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
    private static final int DIAGNOSTIC_WORD_LIMIT = 200;
    private static final int DIAGNOSTIC_LOG_LINES = 120;
    private static final int DIAGNOSTIC_LOG_CHAR_LIMIT = 24_000;

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

    void logToolCall(String sessionId, String name, String args) {
        diagnosticLogs.computeIfAbsent(sessionId, k -> new LinkedList<>())
                .add("Tool call `" + name + "` args: " + truncateWords(oneLine(args), DIAGNOSTIC_WORD_LIMIT));
    }

    void logToolResult(String sessionId, String name, String result) {
        diagnosticLogs.computeIfAbsent(sessionId, k -> new LinkedList<>())
                .add("Tool result `" + name + "`: " + truncateWords(oneLine(result), DIAGNOSTIC_WORD_LIMIT));
    }

    void logAgentError(String sessionId, String message) {
        diagnosticLogs.computeIfAbsent(sessionId, k -> new LinkedList<>())
                .add("Agent error: " + truncateWords(oneLine(message), DIAGNOSTIC_WORD_LIMIT));
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
        return body.toString();
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
        StringBuilder response = new StringBuilder();
        try {
            agentClient.streamChat(content, sessionId, new DiscordAgentClient.Handler() {
                @Override
                public void onToken(String text) {
                    response.append(text);
                }

                @Override
                public void onToolCall(String name, String args) {
                    logToolCall(sessionId, name, args);
                    event.getChannel().sendTyping().queue();
                    event.getChannel().sendMessage("Calling **" + name + "**...").queue();
                }

                @Override
                public void onToolResult(String name, String result) {
                    String resultText = result == null ? "" : result;
                    logToolResult(sessionId, name, resultText);
                    String preview = resultText.length() > 200 ? resultText.substring(0, 200) + "..." : resultText;
                    event.getChannel().sendMessage("**" + name + "** result:\n```\n" + preview + "\n```").queue();
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
                        .addComponents(ActionRow.of(reportButton))
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
