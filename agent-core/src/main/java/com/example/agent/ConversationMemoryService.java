package com.example.agent;

import com.example.agent.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Short-term, per-session conversation memory: the agent's working memory for a single chat
 * session. Stores the recent user/assistant turns through a {@link ConversationStore} and replays
 * them back into the model's context on the next request so it remembers the conversation so far.
 *
 * <p>Implements the standard sliding-window buffer ({@code conversation.memory.max-messages}):
 * only the most recent turns are kept, bounding the prompt size. Only the user prompt and the final
 * assistant answer are retained per turn — the intermediate tool-call scaffolding is deliberately
 * dropped, keeping the stored history a valid, self-contained transcript that can be trimmed at any
 * point without orphaning a tool result.
 *
 * <p>Both operations are best-effort: storage failures are logged and swallowed so the agent loop
 * keeps working. Only created when {@code conversation.memory.enabled} is true (the default).
 */
@Service
@ConditionalOnProperty(prefix = "conversation.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ConversationStore store;
    private final ConversationMemoryProperties properties;

    public ConversationMemoryService(ConversationStore store, ConversationMemoryProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Returns the prior turns for {@code sessionId} to prepend before the current prompt, oldest
     * first. Empty when there is no session id or no stored history (never throws).
     */
    public List<ChatMessage> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            return store.load(sessionId);
        } catch (Exception e) {
            log.warn("Conversation history load failed for session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Appends a completed user/assistant exchange to the session, trimming to the sliding-window
     * size. No-op when there is no session id or no answer (never throws).
     */
    public void record(String sessionId, String userPrompt, String assistantAnswer) {
        if (sessionId == null || sessionId.isBlank()
                || assistantAnswer == null || assistantAnswer.isBlank()) {
            return;
        }
        try {
            store.append(sessionId,
                    List.of(ChatMessage.user(userPrompt), ChatMessage.assistant(assistantAnswer)),
                    properties.maxMessages());
            log.debug("Recorded turn for session {}", sessionId);
        } catch (Exception e) {
            log.warn("Conversation history store failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
