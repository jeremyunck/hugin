package com.example.integration;

import com.example.agent.CloudAgentProperties;
import com.example.agent.ConversationMemoryProperties;
import com.example.agent.EmbeddingProperties;
import com.example.agent.LlmProperties;
import com.example.agent.MemoryProperties;
import com.example.agent.scheduler.SchedulerProperties;
import com.example.agent.tool.LocalToolProperties;
import com.example.integration.auth.AuthJwtProperties;
import com.example.integration.github.GitHubProperties;
import com.example.integration.google.GoogleWorkspaceProperties;
import com.example.integration.sandbox.SandboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.example")
@EnableConfigurationProperties({LlmProperties.class, LocalToolProperties.class,
        EmbeddingProperties.class, MemoryProperties.class, ConversationMemoryProperties.class,
        CloudAgentProperties.class, SchedulerProperties.class, GoogleWorkspaceProperties.class,
        GitHubProperties.class, AuthJwtProperties.class, SandboxProperties.class})
public class HuginApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuginApplication.class, args);
    }
}
