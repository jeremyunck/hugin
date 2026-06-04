package com.example.integration;

import com.example.agent.CloudAgentProperties;
import com.example.agent.ConversationMemoryProperties;
import com.example.agent.EmbeddingProperties;
import com.example.agent.LlmProperties;
import com.example.agent.MemoryProperties;
import com.example.agent.scheduler.SchedulerProperties;
import com.example.agent.tool.LocalToolProperties;
import com.example.integration.google.GoogleWorkspaceProperties;
import com.example.integration.auth.AuthJwtProperties;
import com.example.mcpclient.config.McpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.example")
@EnableConfigurationProperties({McpProperties.class, LlmProperties.class, LocalToolProperties.class,
        EmbeddingProperties.class, MemoryProperties.class, ConversationMemoryProperties.class,
        CloudAgentProperties.class, SchedulerProperties.class, GoogleWorkspaceProperties.class,
        AuthJwtProperties.class})
public class McpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }
}
