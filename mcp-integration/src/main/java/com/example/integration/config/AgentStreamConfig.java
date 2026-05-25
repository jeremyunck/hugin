package com.example.integration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread pool that runs the (blocking) agent loop behind the {@code /api/agent/stream} SSE
 * endpoint, off the request thread. Spring shuts the executor down on application close.
 */
@Configuration
public class AgentStreamConfig {

    @Bean
    public ExecutorService agentStreamExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-stream");
            t.setDaemon(true);
            return t;
        });
    }
}
