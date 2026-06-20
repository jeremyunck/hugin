package com.example.integration.service;

import com.example.agent.model.ChatAttachment;
import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ChatSessionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ChatAttachment>> ATTACHMENTS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsertSession(String sessionId, String owner, String title, String mode, Instant now) {
        int updated = jdbcTemplate.update("""
                update chat_sessions
                set title = ?, mode = ?, updated_at = ?
                where id = ? and owner_username = ?
                """,
                title, mode, Timestamp.from(now), sessionId, owner);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into chat_sessions (id, owner_username, title, mode, last_event_seq, created_at, updated_at)
                    values (?, ?, ?, ?, 0, ?, ?)
                    """,
                    sessionId, owner, title, mode, Timestamp.from(now), Timestamp.from(now));
        }
    }

    public boolean sessionExistsForOwner(String sessionId, String owner) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from chat_sessions
                where id = ? and owner_username = ?
                """, Integer.class, sessionId, owner);
        return count != null && count > 0;
    }

    public boolean sessionExists(String sessionId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from chat_sessions where id = ?", Integer.class, sessionId);
        return count != null && count > 0;
    }

    /**
     * Allocates the next per-session event sequence number.
     *
     * <p>The {@code for update} clause takes an exclusive lock on the {@code chat_sessions} row that
     * is held until the surrounding transaction commits, so concurrent callers in other transactions
     * block here until the in-flight allocation commits its incremented {@code last_event_seq}. That
     * makes the read-then-update atomic across transactions and guarantees gap-free, collision-free
     * sequence numbers. Callers must therefore invoke this inside a transaction (as
     * {@link ChatSessionService} does); {@code ChatSessionRepositoryTest} exercises the concurrent
     * path to prove no sequence number is reused.
     */
    public long nextSeq(String sessionId, Instant now) {
        List<Long> rows = jdbcTemplate.query(
                "select last_event_seq from chat_sessions where id = ? for update",
                (rs, rowNum) -> rs.getLong("last_event_seq"),
                sessionId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Chat session not found: " + sessionId);
        }
        long nextSeq = rows.get(0) + 1;
        jdbcTemplate.update("""
                update chat_sessions
                set last_event_seq = ?, updated_at = ?
                where id = ?
                """, nextSeq, Timestamp.from(now), sessionId);
        return nextSeq;
    }

    public void insertMessage(String messageId, String sessionId, String runId, String role, String content, String status, Instant now) {
        jdbcTemplate.update("""
                insert into chat_messages (id, session_id, run_id, role, content, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                messageId, sessionId, runId, role, content, status, Timestamp.from(now), Timestamp.from(now));
    }

    public void appendMessageContent(String messageId, String delta, Instant now) {
        jdbcTemplate.update("""
                update chat_messages
                set content = concat(content, ?), updated_at = ?
                where id = ?
                """, delta, Timestamp.from(now), messageId);
    }

    public void updateMessageStatus(String messageId, String status, Instant now) {
        jdbcTemplate.update("""
                update chat_messages
                set status = ?, updated_at = ?
                where id = ?
                """, status, Timestamp.from(now), messageId);
    }

    public void insertRun(String runId, String sessionId, String mode, String status, Instant now) {
        jdbcTemplate.update("""
                insert into agent_runs (id, session_id, mode, status, started_at)
                values (?, ?, ?, ?, ?)
                """, runId, sessionId, mode, status, Timestamp.from(now));
    }

    public void updateRunStatus(String runId, String status, String errorMessage, Instant completedAt) {
        jdbcTemplate.update("""
                update agent_runs
                set status = ?, error_message = ?, completed_at = ?
                where id = ?
                """, status, errorMessage, completedAt == null ? null : Timestamp.from(completedAt), runId);
    }

    public ChatSessionEvent insertEvent(String sessionId,
                                        String runId,
                                        String messageId,
                                        long seq,
                                        String type,
                                        String role,
                                        String content,
                                        Map<String, Object> metadata,
                                        Instant createdAt) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into chat_events (id, session_id, run_id, message_id, seq, type, role, content, metadata, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                sessionId,
                runId,
                messageId,
                seq,
                type,
                role,
                content,
                writeJson(metadata),
                Timestamp.from(createdAt));
        return new ChatSessionEvent(eventId, sessionId, runId, messageId, seq, type, role, content, metadata, createdAt);
    }

    public List<ChatSessionEvent> readEvents(String sessionId, long afterSeq) {
        return jdbcTemplate.query("""
                select id, session_id, run_id, message_id, seq, type, role, content, metadata, created_at
                from chat_events
                where session_id = ? and seq > ?
                order by seq asc
                """, chatEventRowMapper(), sessionId, afterSeq);
    }

    public List<ChatMessage> buildPriorMessages(String sessionId, long beforeSeq) {
        List<ChatSessionEvent> events = jdbcTemplate.query("""
                select id, session_id, run_id, message_id, seq, type, role, content, metadata, created_at
                from chat_events
                where session_id = ? and seq < ?
                order by seq asc
                """, chatEventRowMapper(), sessionId, beforeSeq);

        Map<String, ReplayMessage> messagesById = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (ChatSessionEvent event : events) {
            String messageId = event.messageId();
            if (messageId == null || messageId.isBlank()) {
                continue;
            }
            switch (event.type()) {
                case "user_message_created" -> {
                    ReplayMessage message = messagesById.computeIfAbsent(messageId, ignored -> new ReplayMessage("user"));
                    message.content = event.content() == null ? "" : event.content();
                    message.attachments = readAttachments(event.metadata().get("attachments"));
                    if (!order.contains(messageId)) {
                        order.add(messageId);
                    }
                }
                case "assistant_message_started" -> {
                    messagesById.computeIfAbsent(messageId, ignored -> new ReplayMessage("assistant"));
                    if (!order.contains(messageId)) {
                        order.add(messageId);
                    }
                }
                case "assistant_reasoning" -> {
                    ReplayMessage message = messagesById.computeIfAbsent(messageId, ignored -> new ReplayMessage("assistant"));
                    message.reasoning += event.content() == null ? "" : event.content();
                    if (!order.contains(messageId)) {
                        order.add(messageId);
                    }
                }
                case "assistant_token" -> {
                    ReplayMessage message = messagesById.computeIfAbsent(messageId, ignored -> new ReplayMessage("assistant"));
                    message.content += event.content() == null ? "" : event.content();
                    if (!order.contains(messageId)) {
                        order.add(messageId);
                    }
                }
                case "assistant_message_completed", "assistant_message_error" -> {
                    ReplayMessage message = messagesById.computeIfAbsent(messageId, ignored -> new ReplayMessage("assistant"));
                    if ((message.content == null || message.content.isBlank()) && event.content() != null && !event.content().isBlank()) {
                        message.content = event.content();
                    }
                    if (!order.contains(messageId)) {
                        order.add(messageId);
                    }
                }
                default -> {
                }
            }
        }

        List<ChatMessage> transcript = new ArrayList<>();
        for (String messageId : order) {
            ReplayMessage message = messagesById.get(messageId);
            if (message == null) {
                continue;
            }
            if ("user".equals(message.role)) {
                transcript.add(ChatMessage.user(message.content == null ? "" : message.content, message.attachments));
            } else if ("assistant".equals(message.role) && message.content != null && !message.content.isBlank()) {
                transcript.add(ChatMessage.assistant(message.content, message.reasoning.isBlank() ? null : message.reasoning));
            }
        }
        return transcript;
    }

    public Optional<String> readMessageContent(String messageId) {
        List<String> results = jdbcTemplate.query("select content from chat_messages where id = ?",
                (rs, rowNum) -> rs.getString("content"), messageId);
        return results.stream().findFirst();
    }

    private RowMapper<ChatSessionEvent> chatEventRowMapper() {
        return (rs, rowNum) -> new ChatSessionEvent(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("run_id"),
                rs.getString("message_id"),
                rs.getLong("seq"),
                rs.getString("type"),
                rs.getString("role"),
                rs.getString("content"),
                readJson(rs.getString("metadata")),
                rs.getTimestamp("created_at").toInstant());
    }

    private String writeJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize chat event metadata", e);
        }
    }

    private Map<String, Object> readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<ChatAttachment> readAttachments(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(raw, ATTACHMENTS_TYPE);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static final class ReplayMessage {
        private final String role;
        private String content = "";
        private String reasoning = "";
        private List<ChatAttachment> attachments = List.of();

        private ReplayMessage(String role) {
            this.role = role;
        }
    }
}
