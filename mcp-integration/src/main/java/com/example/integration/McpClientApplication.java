package com.example.integration;

import com.example.agent.LlmProperties;
import com.example.agent.tool.LocalToolProperties;
import com.example.mcpclient.config.McpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.example")
@EnableConfigurationProperties({McpProperties.class, LlmProperties.class, LocalToolProperties.class})
public class McpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }
}
