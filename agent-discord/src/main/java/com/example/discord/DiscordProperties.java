package com.example.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Discord bot front-end.
 *
 * <p>Required in the Discord Developer Portal (Applications → Bot → Privileged Gateway Intents):
 * <ul>
 *   <li><b>Message Content Intent</b> — needed to read message text.
 * </ul>
 */
@ConfigurationProperties("discord")
public class DiscordProperties {

    /** Bot token from the Discord Developer Portal. Set via {@code DISCORD_BOT_TOKEN}. */
    private String botToken;

    /** Guild channel IDs (as strings) the bot will listen and respond to. */
    private List<String> allowedChannelIds = new ArrayList<>();

    /**
     * When {@code true}, the bot only responds in guild channels when directly {@literal @}mentioned.
     * DM behaviour is unaffected by this flag.
     */
    private boolean mentionOnly = false;

    /** When {@code true} (the default), the bot responds to direct messages. */
    private boolean respondToDms = true;

    /** Base URL of the running mcp-integration server. */
    private String serverUrl = "http://localhost:8080";

    /** Optional {@code X-API-Key} header value; required when the server has {@code agent.api-key} set. */
    private String apiKey;

    /** Optional model override sent with every request; falls back to the server's {@code llm.model}. */
    private String model;

    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) { this.botToken = botToken; }

    public List<String> getAllowedChannelIds() { return allowedChannelIds; }
    public void setAllowedChannelIds(List<String> allowedChannelIds) {
        this.allowedChannelIds = allowedChannelIds != null ? allowedChannelIds : new ArrayList<>();
    }

    public boolean isMentionOnly() { return mentionOnly; }
    public void setMentionOnly(boolean mentionOnly) { this.mentionOnly = mentionOnly; }

    public boolean isRespondToDms() { return respondToDms; }
    public void setRespondToDms(boolean respondToDms) { this.respondToDms = respondToDms; }

    public String getServerUrl() {
        String url = (serverUrl == null || serverUrl.isBlank()) ? "http://localhost:8080" : serverUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
