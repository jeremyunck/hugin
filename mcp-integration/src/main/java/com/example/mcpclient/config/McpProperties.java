package com.example.mcpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties("mcp")
public record McpProperties(String configFile, Duration initTimeout) {

    /** Default MCP client initialization handshake timeout when none is configured. */
    public static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(120);

    // The convenience constructor below means this record has more than one constructor, so Spring
    // can't infer which to use for property binding — pin the canonical one explicitly.
    @ConstructorBinding
    public McpProperties {
        if (configFile == null || configFile.isBlank()) {
            configFile = "./mcp-servers.json";
        }
        if (initTimeout == null || initTimeout.isZero() || initTimeout.isNegative()) {
            initTimeout = DEFAULT_INIT_TIMEOUT;
        }
    }

    /** Convenience constructor for callers/tests that only set the config path. */
    public McpProperties(String configFile) {
        this(configFile, null);
    }

    public String resolvedConfigFile() {
        if (configFile.startsWith("~/")) {
            return System.getProperty("user.home") + configFile.substring(1);
        }
        return configFile;
    }
}
