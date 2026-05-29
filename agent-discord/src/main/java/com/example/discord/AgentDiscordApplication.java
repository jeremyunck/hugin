package com.example.discord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Discord bot front-end for the agent. Runs as a plain service application (no web server) and
 * forwards messages from configured guild channels and/or DMs to the {@code mcp-integration}
 * server, replying with the agent's response.
 */
@SpringBootApplication
@EnableConfigurationProperties(DiscordProperties.class)
public class AgentDiscordApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentDiscordApplication.class, args);
    }
}
