package com.example.terminal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Interactive terminal front-end for the agent. Runs as a plain console application (no web
 * server) and streams responses from the {@code mcp-integration} server's
 * {@code /api/agent/stream} endpoint, so prompts are answered token-by-token in real time.
 */
@SpringBootApplication
@EnableConfigurationProperties(TerminalProperties.class)
public class AgentTerminalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentTerminalApplication.class, args);
    }
}
