package com.example.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for short-term, per-session conversation memory (the sliding window of recent turns
 * replayed into the model on each request).
 *
 * <p>Enabled by default. The {@code enabled} flag is consumed by the {@code @ConditionalOnProperty}
 * on {@link ConversationMemoryService} / {@link InMemoryConversationStore}, not read here.
 *
 * @param enabled     master switch for short-term memory (default true)
 * @param maxMessages sliding-window size: the most recent N stored messages kept per session
 * @param ttl         optional idle-retention limit; when null/blank, history is retained until trimmed by maxMessages
 * @param storeFile   JSON file used to persist short-term conversation history across restarts
 */
@ConfigurationProperties("conversation.memory")
public record ConversationMemoryProperties(boolean enabled, int maxMessages, Duration ttl, String storeFile) {
    public ConversationMemoryProperties {
        if (maxMessages <= 0) {
            maxMessages = 20;
        }
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            ttl = null;
        }
        if (storeFile == null || storeFile.isBlank()) {
            storeFile = "./conversation-memory.json";
        }
    }
}
