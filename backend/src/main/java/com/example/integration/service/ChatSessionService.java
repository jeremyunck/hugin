package com.example.integration.service;

import com.example.agent.AgentRunCancelledException;
import com.example.agent.AgentRunRegistry;
import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.ChatAttachment;
import com.example.integration.controller.ChatSessionMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionRepository repository;
    private final ChatSessionEventBroker broker;
    private final AgentService agentService;
    private final ExecutorService agentStreamExecutor;
    private final AgentRunRegistry runRegistry;
    private final TransactionTemplate transactionTemplate;

    public ChatSessionService(ChatSessionRepository repository,
                              ChatSessionEventBroker broker,
                              AgentService agentService,
                              ExecutorService agentStreamExecutor,
                              AgentRunRegistry runRegistry,
                              TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.broker = broker;
        this.agentService = agentService;
        this.agentStreamExecutor = agentStreamExecutor;
        this.runRegistry = runRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    public ChatSessionMessageAcceptance createMessage(String sessionId, String owner, ChatSessionMessageRequest request) {
        // Reject a session owned by someone else up front. Without this the owner-scoped upsert would
        // fail to update, fall through to an insert, and blow up on the primary key (a 500) instead of
        // a clean not-found. Mirror readEvents and avoid leaking the session's existence.
        if (repository.sessionExists(sessionId) && !repository.sessionExistsForOwner(sessionId, owner)) {
            throw new ResponseStatusException(NOT_FOUND, "Chat session not found");
        }
        ChatSessionMessageAcceptance accepted = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            String trimmedContent = request.content() == null ? "" : request.content().trim();
            String title = request.title() == null || request.title().isBlank() ? trimmedContent : request.title().trim();
            String mode = request.mode() == null || request.mode().isBlank() ? "CHAT" : request.mode().trim();
            String userMessageId = UUID.randomUUID().toString();
            String runId = UUID.randomUUID().toString();

            repository.upsertSession(sessionId, owner, title, mode, now);
            repository.insertMessage(userMessageId, sessionId, null, "user", trimmedContent, "completed", now);
            long lastSeq = appendEvent(sessionId, runId, userMessageId, "user_message_created", "user", trimmedContent,
                    Map.of("attachments", request.attachments() == null ? List.of() : request.attachments()));
            repository.insertRun(runId, sessionId, mode, "queued", now);
            return new ChatSessionMessageAcceptance(sessionId, userMessageId, runId, lastSeq);
        });
        agentStreamExecutor.execute(() -> runAgent(sessionId, owner, accepted.runId(), accepted.lastSeq(), request));
        return accepted;
    }

    public List<ChatSessionEvent> readEvents(String sessionId, String owner, long afterSeq) {
        if (repository.sessionExists(sessionId) && !repository.sessionExistsForOwner(sessionId, owner)) {
            throw new ResponseStatusException(NOT_FOUND, "Chat session not found");
        }
        return repository.readEvents(sessionId, Math.max(0, afterSeq));
    }

    public boolean allowStream(String sessionId, String owner) {
        return !repository.sessionExists(sessionId) || repository.sessionExistsForOwner(sessionId, owner);
    }

    private void runAgent(String sessionId,
                          String owner,
                          String runId,
                          long userMessageSeq,
                          ChatSessionMessageRequest request) {
        String assistantMessageId = UUID.randomUUID().toString();
        AgentRequest agentRequest = new AgentRequest(
                request.content(),
                request.attachments(),
                request.model(),
                request.reasoningEffort(),
                sessionId,
                repository.buildPriorMessages(sessionId, userMessageSeq),
                request.sandboxId(),
                true);
        runRegistry.register(runId, owner, agentRequest, request.model(), Thread.currentThread());
        try {
            markRunStarted(sessionId, runId);
            startAssistantMessage(sessionId, runId, assistantMessageId);
            agentService.chatStream(agentRequest, new AgentStreamListener() {
                @Override
                public void onContent(String delta) {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    appendAssistantToken(sessionId, runId, assistantMessageId, delta);
                }

                @Override
                public void onToolCall(String toolName, String arguments) {
                    appendActivity(sessionId, runId, "tool_call_started", Map.of(
                            "name", toolName == null ? "tool" : toolName,
                            "args", arguments == null ? "" : arguments));
                }

                @Override
                public void onToolResult(String toolName, String result) {
                    appendActivity(sessionId, runId, "tool_call_completed", Map.of(
                            "name", toolName == null ? "tool" : toolName,
                            "result", result == null ? "" : result));
                }
            }, owner);
            completeAssistantMessage(sessionId, runId, assistantMessageId);
        } catch (AgentRunCancelledException e) {
            failAssistantMessage(sessionId, runId, assistantMessageId, e.getMessage() == null ? "Request cancelled." : e.getMessage());
        } catch (Exception e) {
            log.warn("Chat session run failed: {}", runId, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            failAssistantMessage(sessionId, runId, assistantMessageId, message);
        } finally {
            runRegistry.unregister(runId);
        }
    }

    protected void markRunStarted(String sessionId, String runId) {
        transactionTemplate.executeWithoutResult(status -> {
            repository.updateRunStatus(runId, "running", null, null);
            appendEvent(sessionId, runId, null, "run_started", null, null, Map.of());
        });
    }

    protected void startAssistantMessage(String sessionId, String runId, String assistantMessageId) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            repository.insertMessage(assistantMessageId, sessionId, runId, "assistant", "", "streaming", now);
            appendEvent(sessionId, runId, assistantMessageId, "assistant_message_started", "assistant", "", Map.of());
        });
    }

    protected void appendAssistantToken(String sessionId, String runId, String assistantMessageId, String delta) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            repository.appendMessageContent(assistantMessageId, delta, now);
            appendEvent(sessionId, runId, assistantMessageId, "assistant_token", "assistant", delta, Map.of());
        });
    }

    protected void completeAssistantMessage(String sessionId, String runId, String assistantMessageId) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            repository.updateMessageStatus(assistantMessageId, "completed", now);
            appendEvent(sessionId, runId, assistantMessageId, "assistant_message_completed", "assistant",
                    repository.readMessageContent(assistantMessageId).orElse(""), Map.of());
            repository.updateRunStatus(runId, "completed", null, now);
            appendEvent(sessionId, runId, null, "run_completed", null, null, Map.of());
        });
    }

    protected void failAssistantMessage(String sessionId, String runId, String assistantMessageId, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            if (repository.readMessageContent(assistantMessageId).isEmpty()) {
                repository.insertMessage(assistantMessageId, sessionId, runId, "assistant", "", "error", now);
            } else {
                repository.updateMessageStatus(assistantMessageId, "error", now);
            }
            appendEvent(sessionId, runId, assistantMessageId, "assistant_message_error", "assistant", message, Map.of("message", message));
            repository.updateRunStatus(runId, "error", message, now);
            appendEvent(sessionId, runId, null, "run_error", null, null, Map.of("message", message));
        });
    }

    protected void appendActivity(String sessionId, String runId, String type, Map<String, Object> metadata) {
        transactionTemplate.executeWithoutResult(status ->
                appendEvent(sessionId, runId, null, type, null, null, metadata));
    }

    private long appendEvent(String sessionId,
                             String runId,
                             String messageId,
                             String type,
                             String role,
                             String content,
                             Map<String, Object> metadata) {
        Instant now = Instant.now();
        long seq = repository.nextSeq(sessionId, now);
        ChatSessionEvent event = repository.insertEvent(
                sessionId,
                runId,
                messageId,
                seq,
                type,
                role,
                content,
                metadata == null ? Map.of() : new LinkedHashMap<>(metadata),
                now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Publish only after the write commits so subscribers never observe an event that a
            // rollback later discards.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broker.publish(event);
                }
            });
        } else {
            // Defensive fallback: every caller appends inside a transaction, so this branch is not
            // exercised today. If a future caller appends without one, the event is already
            // committed (auto-commit) by the time we publish, so there is still nothing to roll back.
            broker.publish(event);
        }
        return seq;
    }
}
