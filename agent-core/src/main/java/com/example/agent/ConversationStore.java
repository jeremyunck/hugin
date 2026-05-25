package com.example.agent;

import com.example.agent.model.ChatMessage;

import java.util.List;

/**
 * Storage abstraction for short-term, per-session conversation memory: the recent verbatim turns
 * of a single chat session, keyed by an opaque {@code sessionId}.
 *
 * <p>This is distinct from {@link MemoryStore}, which backs long-term semantic memory recalled by
 * embedding similarity across all sessions. Here messages are simply the last few turns of one
 * ongoing conversation, replayed back into the model's context so it remembers what was just said.
 *
 * <p>The default {@link InMemoryConversationStore} keeps history in-process; an implementation
 * backed by Redis or another shared store can be substituted to share sessions across instances.
 */
public interface ConversationStore {

    /** Returns the stored messages for {@code sessionId}, oldest first, or an empty list if none. */
    List<ChatMessage> load(String sessionId);

    /**
     * Atomically appends {@code newMessages} to the session's history and trims the result to the
     * most recent {@code maxMessages}.
     *
     * <p>Atomicity matters: concurrent requests for the same session must not lose a turn through a
     * read-modify-write race, so implementations perform the append-and-trim as a single update.
     */
    void append(String sessionId, List<ChatMessage> newMessages, int maxMessages);
}
