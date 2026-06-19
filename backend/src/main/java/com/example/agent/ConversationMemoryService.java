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
 * only the most recent transcript messages are kept, bounding the prompt size. A completed turn may
 * include assistant tool-call scaffolding and tool results, so the stored transcript remains rich
 * enough for client-side catch-up and exact replay.
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
     * size. This overload is for legacy text-only callers; attachment-bearing turns should call the
     * {@link #record(String, ChatMessage, String)} overload so the user message is preserved verbatim.
     * No-op when there is no session id or no answer (never throws).
     */
    public void record(String sessionId, String userPrompt, String assistantAnswer) {
        record(sessionId, ChatMessage.user(userPrompt), assistantAnswer);
    }

    /**
     * Appends a completed user/assistant exchange to the session, trimming to the sliding-window
     * size. No-op when there is no session id or no answer (never throws).
     */
    public void record(String sessionId, ChatMessage userMessage, String assistantAnswer) {
        if (sessionId == null || sessionId.isBlank()
                || userMessage == null
                || assistantAnswer == null || assistantAnswer.isBlank()) {
            return;
        }
        try {
            store.append(sessionId,
                    List.of(userMessage, ChatMessage.assistant(assistantAnswer)),
                    properties.maxMessages());
            log.debug("Recorded turn for session {}", sessionId);
        } catch (Exception e) {
            log.warn("Conversation history store failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Drops all stored history for {@code sessionId}, so a deleted chat leaves nothing behind to be
     * replayed later. No-op when there is no session id (never throws).
     */
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            store.delete(sessionId);
            log.debug("Deleted conversation history for session {}", sessionId);
        } catch (Exception e) {
            log.warn("Conversation history delete failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Appends a completed turn transcript to the session exactly as it should be replayed later.
     * No-op when there is no session id or the transcript is empty (never throws).
     */
    public void recordMessages(String sessionId, List<ChatMessage> messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            store.append(sessionId, List.copyOf(messages), properties.maxMessages());
            log.debug("Recorded transcript for session {}", sessionId);
        } catch (Exception e) {
            log.warn("Conversation history store failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
