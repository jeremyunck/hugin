package com.example.integration.service;

import com.example.agent.AgentRunCancelledException;
import com.example.agent.AgentRunRegistry;
import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.ChatMessage;
import com.example.integration.controller.ChatSessionMessageRequest;
import com.example.integration.modelsettings.ModelContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /**
     * Fraction of the model's context window that, once a request's estimated prompt size reaches it,
     * triggers a background compaction of the conversation before the run proceeds.
     */
    private static final double COMPACTION_THRESHOLD = 0.80;
    /** Token headroom reserved for the model's response when checking whether the next request fits. */
    private static final int RESPONSE_TOKEN_RESERVE = 4096;
    /** Rough allowance for system prompts, tool schemas, and framing not present in prior messages. */
    private static final int SYSTEM_OVERHEAD_TOKENS = 1500;

    private final ChatSessionRepository repository;
    private final ChatSessionEventBroker broker;
    private final AgentService agentService;
    private final ExecutorService agentStreamExecutor;
    private final AgentRunRegistry runRegistry;
    private final TransactionTemplate transactionTemplate;
    private final ModelContextService modelContextService;
    private final String defaultModel;

    public ChatSessionService(ChatSessionRepository repository,
                              ChatSessionEventBroker broker,
                              AgentService agentService,
                              ExecutorService agentStreamExecutor,
                              AgentRunRegistry runRegistry,
                              TransactionTemplate transactionTemplate,
                              ModelContextService modelContextService,
                              @Value("${llm.model:}") String defaultModel) {
        this.repository = repository;
        this.broker = broker;
        this.agentService = agentService;
        this.agentStreamExecutor = agentStreamExecutor;
        this.runRegistry = runRegistry;
        this.transactionTemplate = transactionTemplate;
        this.modelContextService = modelContextService;
        this.defaultModel = defaultModel;
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
        List<ChatMessage> priorMessages = repository.buildPriorMessages(sessionId, userMessageSeq);
        AgentRequest agentRequest = new AgentRequest(
                request.content(),
                request.attachments(),
                request.model(),
                request.reasoningEffort(),
                sessionId,
                priorMessages,
                request.sandboxId(),
                true);
        runRegistry.register(runId, owner, agentRequest, request.model(), Thread.currentThread());
        // Holds the id of the assistant message currently being streamed, or null when none is open.
        // A new bubble is opened lazily on the first token/reasoning after a tool call, so assistant
        // text and tool-call cards interleave in the event log exactly as they occur.
        AtomicReference<String> openAssistantId = new AtomicReference<>();
        try {
            markRunStarted(sessionId, runId);
            AgentRequest effectiveRequest = maybeCompact(sessionId, runId, agentRequest, priorMessages, request);
            // The model's context window (when known) lets the agent loop cap its growing transcript so
            // a turn with large tool results does not overflow and get rejected by the provider.
            Long contextLimit = modelContextService
                    .contextLimit(firstNonBlank(request.model(), defaultModel))
                    .orElse(null);
            AgentResponse response = agentService.chatStream(effectiveRequest, new AgentStreamListener() {
                @Override
                public void onContent(String delta) {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    appendAssistantToken(sessionId, runId, ensureAssistantMessage(sessionId, runId, openAssistantId), delta);
                }

                @Override
                public void onReasoning(String delta) {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    appendAssistantReasoning(sessionId, runId, ensureAssistantMessage(sessionId, runId, openAssistantId), delta);
                }

                @Override
                public void onToolCall(String toolCallId, String toolName, String arguments) {
                    // Close the in-progress assistant bubble so the tool card renders after it.
                    String open = openAssistantId.getAndSet(null);
                    if (open != null) {
                        completeAssistantMessage(sessionId, runId, open, null);
                    }
                    appendActivity(sessionId, runId, "tool_call_started", Map.of(
                            "callId", toolCallId == null ? "" : toolCallId,
                            "name", toolName == null ? "tool" : toolName,
                            "args", arguments == null ? "" : arguments));
                }

                @Override
                public void onToolResult(String toolCallId, String toolName, String result) {
                    appendActivity(sessionId, runId, "tool_call_completed", Map.of(
                            "callId", toolCallId == null ? "" : toolCallId,
                            "name", toolName == null ? "tool" : toolName,
                            "result", result == null ? "" : result));
                }
            }, owner, contextLimit);
            String fallback = response == null ? null : response.response();
            String open = openAssistantId.getAndSet(null);
            if (open == null && fallback != null && !fallback.isBlank()) {
                // The run ended without an open bubble (e.g. a non-streamed fallback answer). Surface
                // the final answer as its own assistant message so the user still sees a reply.
                open = ensureAssistantMessage(sessionId, runId, openAssistantId);
                openAssistantId.set(null);
            }
            if (open != null) {
                completeAssistantMessage(sessionId, runId, open, fallback);
            }
            completeRun(sessionId, runId);
        } catch (AgentRunCancelledException e) {
            failRun(sessionId, runId, openAssistantId.get(), e.getMessage() == null ? "Request cancelled." : e.getMessage());
        } catch (Exception e) {
            log.warn("Chat session run failed: {}", runId, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            failRun(sessionId, runId, openAssistantId.get(), message);
        } catch (Throwable t) {
            // Errors (OutOfMemoryError, StackOverflowError, thread death, ...) bypass the Exception
            // handler above. Without terminating the run here the event log would be left with a
            // dangling run_started and the composer would stay disabled forever. Emit the terminal
            // events best-effort, then rethrow to preserve normal JVM error semantics.
            log.error("Chat session run terminated abnormally: {}", runId, t);
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            try {
                failRun(sessionId, runId, openAssistantId.get(), message);
            } catch (Throwable ignored) {
                // The environment may be too degraded (e.g. OOM) to persist; don't mask the original.
            }
            throw t;
        } finally {
            runRegistry.unregister(runId);
        }
    }

    /**
     * Reconciles runs orphaned by a crash or restart. A run's terminal event is only written by the
     * worker thread that owns it; if the process dies mid-run that thread is gone, leaving the run
     * {@code running}/{@code queued} in the log with no {@code run_completed}/{@code run_error}. The UI
     * projects such a run as perpetually busy and disables the composer. On startup every worker is by
     * definition gone, so any unfinished run is an orphan: terminate each so its thread unsticks.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphanedRuns() {
        List<ChatSessionRepository.UnfinishedRun> orphans;
        try {
            orphans = repository.findUnfinishedRuns();
        } catch (Exception e) {
            log.warn("Could not scan for orphaned chat runs on startup: {}", e.getMessage());
            return;
        }
        if (orphans.isEmpty()) {
            return;
        }
        log.info("Reconciling {} orphaned chat run(s) left unfinished by a previous process", orphans.size());
        for (ChatSessionRepository.UnfinishedRun orphan : orphans) {
            try {
                terminateUnfinishedRun(orphan.sessionId(), orphan.runId(), "Run interrupted by a server restart.");
            } catch (Exception e) {
                log.warn("Failed to reconcile orphaned run {}: {}", orphan.runId(), e.getMessage());
            }
        }
    }

    /**
     * Stops the active run for a session at the user's request. If the run's worker is still live in
     * this process it is interrupted (and emits {@code run_error} itself as it unwinds). If no live
     * worker is found the run is an orphan (e.g. survived a restart), so it is terminated directly so
     * the composer unsticks. Returns whether anything was stopped.
     */
    public boolean cancelRun(String sessionId, String owner) {
        if (repository.sessionExists(sessionId) && !repository.sessionExistsForOwner(sessionId, owner)) {
            throw new ResponseStatusException(NOT_FOUND, "Chat session not found");
        }
        Optional<String> runId = repository.findActiveRunId(sessionId);
        if (runId.isEmpty()) {
            return false;
        }
        String id = runId.get();
        if (runRegistry.cancel(owner, id)) {
            return true;
        }
        // No live worker: the run finished racing this call, or it was orphaned by a restart. Only the
        // latter still needs a terminal event; terminateUnfinishedRun no-ops if it already finished.
        return terminateUnfinishedRun(sessionId, id, "Run stopped.");
    }

    /**
     * Forcibly writes the terminal events for a run that no live worker will finish, guarded against a
     * race with a worker that completes concurrently: it re-checks the persisted status inside the
     * transaction and no-ops if the run already reached a terminal state. Mirrors {@link #failRun} but
     * looks up the open assistant bubble itself since there is no in-memory run context to pass one in.
     */
    protected boolean terminateUnfinishedRun(String sessionId, String runId, String message) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            String current = repository.runStatus(runId).orElse(null);
            if (current == null || "completed".equals(current) || "error".equals(current)) {
                return false;
            }
            Instant now = Instant.now();
            String assistantMessageId = repository.findOpenAssistantMessageId(runId).orElse(null);
            if (assistantMessageId == null) {
                assistantMessageId = UUID.randomUUID().toString();
                repository.insertMessage(assistantMessageId, sessionId, runId, "assistant", "", "streaming", now);
                appendEvent(sessionId, runId, assistantMessageId, "assistant_message_started", "assistant", "", Map.of());
            } else {
                repository.updateMessageStatus(assistantMessageId, "error", now);
            }
            appendEvent(sessionId, runId, assistantMessageId, "assistant_message_error", "assistant", message, Map.of("message", message));
            repository.updateRunStatus(runId, "error", message, now);
            appendEvent(sessionId, runId, null, "run_error", null, null, Map.of("message", message));
            return true;
        }));
    }

    /**
     * Opens a new streaming assistant message if none is currently open, returning the id to append
     * to. Idempotent while a bubble is open: repeated calls return the same id until it is closed.
     */
    private String ensureAssistantMessage(String sessionId, String runId, AtomicReference<String> openAssistantId) {
        String existing = openAssistantId.get();
        if (existing != null) {
            return existing;
        }
        String messageId = UUID.randomUUID().toString();
        startAssistantMessage(sessionId, runId, messageId);
        openAssistantId.set(messageId);
        return messageId;
    }

    /**
     * Compacts the conversation in the background when the next request's estimated size would crowd
     * the active model's context window. Returns the request to actually run: either the original, or
     * a copy whose prior messages have been replaced by a compact summary. A {@code conversation_compacted}
     * event is appended so the UI can show that the thread was compacted. Best-effort — any failure
     * falls back to running the original request unchanged.
     */
    private AgentRequest maybeCompact(String sessionId,
                                      String runId,
                                      AgentRequest agentRequest,
                                      List<ChatMessage> priorMessages,
                                      ChatSessionMessageRequest request) {
        if (priorMessages == null || priorMessages.isEmpty()) {
            return agentRequest;
        }
        String model = firstNonBlank(request.model(), defaultModel);
        Long contextLimit = modelContextService.contextLimit(model).orElse(null);
        if (contextLimit == null) {
            return agentRequest;
        }
        int priorTokens = AgentService.estimateTokens(priorMessages);
        int promptTokens = AgentService.estimateTokens(List.of(ChatMessage.user(request.content())));
        int estimated = priorTokens + promptTokens + SYSTEM_OVERHEAD_TOKENS;
        boolean overThreshold = estimated >= contextLimit * COMPACTION_THRESHOLD;
        boolean nextWouldOverflow = estimated + RESPONSE_TOKEN_RESERVE > contextLimit;
        if (!overThreshold && !nextWouldOverflow) {
            return agentRequest;
        }
        log.info("Compacting session {} before run {}: estimated {} tokens vs context limit {} (model {})",
                sessionId, runId, estimated, contextLimit, model);
        try {
            String summary = agentService.summarizeForCompaction(model, priorMessages);
            if (summary == null || summary.isBlank()) {
                return agentRequest;
            }
            recordCompaction(sessionId, runId, summary);
            List<ChatMessage> compacted = List.of(ChatMessage.system(
                    "Summary of the earlier conversation, compacted to fit the context window:\n" + summary));
            return new AgentRequest(
                    request.content(),
                    request.attachments(),
                    request.model(),
                    request.reasoningEffort(),
                    sessionId,
                    compacted,
                    request.sandboxId(),
                    true);
        } catch (Exception e) {
            log.warn("Compaction failed for session {}; proceeding with full context: {}", sessionId, e.getMessage());
            return agentRequest;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
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

    protected void appendAssistantReasoning(String sessionId, String runId, String assistantMessageId, String delta) {
        transactionTemplate.executeWithoutResult(status ->
                appendEvent(sessionId, runId, assistantMessageId, "assistant_reasoning", "assistant", delta, Map.of()));
    }

    /**
     * Completes a single assistant bubble. {@code fallbackContent} (the agent's final answer) is used
     * only when the streamed message is empty, so non-streamed fallback answers still reach the user.
     * Run completion is handled separately by {@link #completeRun} since a run may contain several
     * assistant bubbles interleaved with tool calls.
     */
    protected void completeAssistantMessage(String sessionId, String runId, String assistantMessageId, String fallbackContent) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            String stored = repository.readMessageContent(assistantMessageId).orElse("");
            String content = (stored == null || stored.isBlank()) && fallbackContent != null && !fallbackContent.isBlank()
                    ? fallbackContent
                    : stored;
            repository.updateMessageStatus(assistantMessageId, "completed", now);
            appendEvent(sessionId, runId, assistantMessageId, "assistant_message_completed", "assistant", content, Map.of());
        });
    }

    protected void completeRun(String sessionId, String runId) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            repository.updateRunStatus(runId, "completed", null, now);
            appendEvent(sessionId, runId, null, "run_completed", null, null, Map.of());
        });
    }

    /**
     * Records a {@code conversation_compacted} marker in the event log. The visible content drives the
     * inline "conversation compacted" notice in the UI, and the {@code summary} metadata lets later
     * runs rebuild a compact prior-message transcript from this point forward.
     */
    protected void recordCompaction(String sessionId, String runId, String summary) {
        transactionTemplate.executeWithoutResult(status ->
                appendEvent(sessionId, runId, null, "conversation_compacted", null,
                        "Conversation compacted to fit the model's context window.",
                        Map.of("summary", summary)));
    }

    /**
     * Fails the run, attaching the error to the open assistant bubble when there is one. When no bubble
     * is open (the failure happened before any output), a fresh assistant message is started so the
     * user still sees the error in the transcript.
     */
    protected void failRun(String sessionId, String runId, String openAssistantId, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            String assistantMessageId = openAssistantId;
            if (assistantMessageId == null) {
                assistantMessageId = UUID.randomUUID().toString();
                repository.insertMessage(assistantMessageId, sessionId, runId, "assistant", "", "streaming", now);
                appendEvent(sessionId, runId, assistantMessageId, "assistant_message_started", "assistant", "", Map.of());
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
