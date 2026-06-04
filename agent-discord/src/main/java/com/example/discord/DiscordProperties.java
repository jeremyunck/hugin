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
     * When {@code true} (the default), the bot only responds in guild channels when directly
     * addressed — either {@literal @}mentioned or replied to. DM behaviour is unaffected by this flag.
     */
    private boolean mentionOnly = true;

    /** When {@code true} (the default), the bot responds to direct messages. */
    private boolean respondToDms = true;

    /** Base URL of the running mcp-integration server. */
    private String serverUrl = "http://localhost:8080";

    /** Optional {@code X-API-Key} header value; required when the server has {@code agent.api-key} set. */
    private String apiKey;

    /**
     * Optional model override sent with every request; falls back to the server's
     * {@code llm.model} when routing is not configured.
     */
    private String model;

    /**
     * Optional routing model used only to classify request complexity. When all three routing
     * fields are configured, Discord sends them instead of the legacy single-model path.
     */
    private String decision;

    /** Model used for complex requests when routing is enabled. */
    private String complex;

    /** Model used for simple requests when routing is enabled. */
    private String simple;

    /** GitHub personal access token for creating issues. Set via {@code GITHUB_TOKEN}. */
    private String githubToken;

    /** GitHub repository in {@code owner/repo} format (e.g. {@code myuser/myrepo}). */
    private String githubRepo;

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

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getComplex() { return complex; }
    public void setComplex(String complex) { this.complex = complex; }

    public String getSimple() { return simple; }
    public void setSimple(String simple) { this.simple = simple; }

    public boolean hasRoutingModels() {
        return decision != null && !decision.isBlank()
                && complex != null && !complex.isBlank()
                && simple != null && !simple.isBlank();
    }

    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken; }

    public String getGithubRepo() { return githubRepo; }
    public void setGithubRepo(String githubRepo) { this.githubRepo = normalizeGithubRepo(githubRepo); }

    static String normalizeGithubRepo(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String repo = value.strip();
        if (repo.startsWith("https://github.com/")) {
            repo = repo.substring("https://github.com/".length());
        } else if (repo.startsWith("http://github.com/")) {
            repo = repo.substring("http://github.com/".length());
        }
        while (repo.endsWith("/")) {
            repo = repo.substring(0, repo.length() - 1);
        }
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - ".git".length());
        }
        return repo;
    }
}
