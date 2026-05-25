package com.example.terminal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the terminal front-end.
 *
 * @param serverUrl base URL of the running mcp-integration server (without a trailing path)
 * @param apiKey    optional value for the {@code X-API-Key} header; required only when the server
 *                  has {@code agent.api-key} configured
 * @param model     optional default model name; when blank the server falls back to {@code llm.model}
 */
@ConfigurationProperties("terminal")
public record TerminalProperties(String serverUrl, String apiKey, String model) {

    public TerminalProperties {
        if (serverUrl == null || serverUrl.isBlank()) {
            serverUrl = "http://localhost:8080";
        }
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
