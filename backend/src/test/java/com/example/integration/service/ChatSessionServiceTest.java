package com.example.integration.service;

import com.example.agent.AgentRunRegistry;
import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.integration.controller.ChatSessionMessageRequest;
import com.example.integration.modelsettings.ModelContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSessionServiceTest {

    private static final String OWNER = "owner";
    private static final String SESSION_ID = "session-1";

    private ChatSessionRepository repository;
    private AgentService agentService;
    private AgentRunRegistry runRegistry;
    private ChatSessionService service;
    private ModelContextService modelContextService;
    private TransactionTemplate transactionTemplate;
    private List<ChatSessionEvent> published;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:chat-svc-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("insert into app_users (username, password_hash) values (?, ?)", OWNER, "hash");
        jdbcTemplate.update("insert into app_users (username, password_hash) values (?, ?)", "intruder", "hash");

        repository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
        ChatSessionEventBroker broker = new ChatSessionEventBroker();
        published = new CopyOnWriteArrayList<>();
        broker.subscribe(SESSION_ID, published::add);

        agentService = mock(AgentService.class);
        runRegistry = mock(AgentRunRegistry.class);

        // Run the agent task inline so createMessage drives the whole run synchronously.
        ExecutorService inlineExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(inlineExecutor).execute(any(Runnable.class));

        transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // No OpenRouter context limit in unit tests, so compaction never triggers by default.
        modelContextService = mock(ModelContextService.class);
        when(modelContextService.contextLimit(any())).thenReturn(java.util.Optional.empty());

        service = new ChatSessionService(
                repository, broker, agentService, inlineExecutor, runRegistry, transactionTemplate,
                modelContextService, "model-x");
    }

    @Test
    void createMessagePersistsOrderedRunLifecycleEvents() {
        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onReasoning("Think");
            listener.onReasoning("ing");
            listener.onContent("Hel");
            listener.onContent("lo");
            listener.onToolCall("call-1", "read_file", "{}");
            listener.onToolResult("call-1", "read_file", "ok");
            return null;
        }).when(agentService).chatStream(any(), any(), any());

        ChatSessionMessageAcceptance accepted = service.createMessage(SESSION_ID, OWNER, request("Hi"));

        List<ChatSessionEvent> events = repository.readEvents(SESSION_ID, 0);
        // The assistant bubble closes when the tool call starts, so tool cards interleave inline:
        // the assistant message is completed before the tool-call events, not after them.
        assertThat(events).extracting(ChatSessionEvent::type).containsExactly(
                "user_message_created",
                "run_started",
                "assistant_message_started",
                "assistant_reasoning",
                "assistant_reasoning",
                "assistant_token",
                "assistant_token",
                "assistant_message_completed",
                "tool_call_started",
                "tool_call_completed",
                "run_completed");

        // Sequence numbers are gap-free and the acceptance points at the user message seq.
        assertThat(events).extracting(ChatSessionEvent::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        assertThat(accepted.lastSeq()).isEqualTo(1L);

        // The completed assistant message holds the concatenated streamed tokens.
        assertThat(events.get(7).content()).isEqualTo("Hello");
        assertThat(events.get(8).metadata()).containsEntry("callId", "call-1");
        assertThat(events.get(9).metadata()).containsEntry("callId", "call-1");

        // Every persisted event was published to the broker after commit, in the same order.
        assertThat(published).extracting(ChatSessionEvent::type)
                .isEqualTo(events.stream().map(ChatSessionEvent::type).toList());
    }

    @Test
    void createMessageRecordsErrorEventsWhenAgentFails() {
        doThrow(new RuntimeException("upstream exploded"))
                .when(agentService).chatStream(any(), any(), any());

        service.createMessage(SESSION_ID, OWNER, request("Hi"));

        List<ChatSessionEvent> events = repository.readEvents(SESSION_ID, 0);
        assertThat(events).extracting(ChatSessionEvent::type).containsExactly(
                "user_message_created",
                "run_started",
                "assistant_message_started",
                "assistant_message_error",
                "run_error");
        assertThat(events.get(3).metadata()).containsEntry("message", "upstream exploded");
    }

    @Test
    void createMessageCompactsConversationWhenContextWindowWouldOverflow() {
        // A tiny advertised context window so the existing history trips the compaction threshold.
        when(modelContextService.contextLimit(any())).thenReturn(java.util.Optional.of(100L));
        // Seed a prior turn so there is something to compact.
        repository.upsertSession(SESSION_ID, OWNER, "Title", "CHAT", Instant.now());
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            long s1 = repository.nextSeq(SESSION_ID, now);
            repository.insertEvent(SESSION_ID, "run-0", "user-0", s1, "user_message_created", "user",
                    "an earlier question", java.util.Map.of(), now);
            long s2 = repository.nextSeq(SESSION_ID, now);
            repository.insertEvent(SESSION_ID, "run-0", "assistant-0", s2, "assistant_message_completed", "assistant",
                    "an earlier answer", java.util.Map.of(), now);
        });

        when(agentService.summarizeForCompaction(any(), any())).thenReturn("Compact briefing of the earlier chat.");
        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onContent("Sure.");
            return null;
        }).when(agentService).chatStream(any(), any(), any());

        service.createMessage(SESSION_ID, OWNER, request("Another question"));

        // A compaction marker was recorded for the UI / future replay.
        List<ChatSessionEvent> events = repository.readEvents(SESSION_ID, 0);
        assertThat(events).extracting(ChatSessionEvent::type).contains("conversation_compacted");
        ChatSessionEvent marker = events.stream()
                .filter(event -> event.type().equals("conversation_compacted")).findFirst().orElseThrow();
        assertThat(marker.metadata()).containsEntry("summary", "Compact briefing of the earlier chat.");

        // The agent ran against the compacted prior context (a single system summary), not the full log.
        org.mockito.ArgumentCaptor<com.example.agent.model.AgentRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.example.agent.model.AgentRequest.class);
        org.mockito.Mockito.verify(agentService).chatStream(captor.capture(), any(), any());
        assertThat(captor.getValue().priorMessages()).hasSize(1);
        assertThat(captor.getValue().priorMessages().get(0).role()).isEqualTo("system");
        assertThat(captor.getValue().priorMessages().get(0).content()).contains("Compact briefing of the earlier chat.");
    }

    @Test
    void createMessageRejectsSessionOwnedByAnotherUser() {
        repository.upsertSession(SESSION_ID, "intruder", "theirs", "CHAT", Instant.now());

        assertThatThrownBy(() -> service.createMessage(SESSION_ID, OWNER, request("Hi")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(thrown -> assertThat(
                        ((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(404));

        // The rejected request must not write any events for the foreign session.
        assertThat(repository.readEvents(SESSION_ID, 0)).isEmpty();
    }

    @Test
    void cancelRunTerminatesAnOrphanedRunWhenNoLiveWorkerRemains() {
        String runId = "run-orphan";
        String assistantId = "assistant-orphan";
        seedRunningRun(runId, assistantId);
        // Default mock: no live worker registered, so the run is treated as orphaned.

        boolean cancelled = service.cancelRun(SESSION_ID, OWNER);

        assertThat(cancelled).isTrue();
        List<ChatSessionEvent> events = repository.readEvents(SESSION_ID, 0);
        assertThat(events).extracting(ChatSessionEvent::type)
                .containsSubsequence("run_started", "assistant_message_error", "run_error");
        // The forced failure attaches its error to the still-open assistant bubble, not a new one.
        ChatSessionEvent error = events.stream()
                .filter(event -> event.type().equals("assistant_message_error")).findFirst().orElseThrow();
        assertThat(error.messageId()).isEqualTo(assistantId);
        assertThat(repository.runStatus(runId)).contains("error");
    }

    @Test
    void cancelRunInterruptsLiveWorkerWithoutForcingTerminalEvents() {
        String runId = "run-live";
        seedRunningRun(runId, "assistant-live");
        // A live worker is still on this run; interrupting it lets it emit its own terminal events.
        when(runRegistry.cancel(OWNER, runId)).thenReturn(true);

        boolean cancelled = service.cancelRun(SESSION_ID, OWNER);

        assertThat(cancelled).isTrue();
        // The service must not race the worker by writing a duplicate terminal event itself.
        assertThat(repository.readEvents(SESSION_ID, 0)).extracting(ChatSessionEvent::type)
                .doesNotContain("run_error");
        assertThat(repository.runStatus(runId)).contains("running");
    }

    @Test
    void cancelRunReturnsFalseWhenNothingIsInFlight() {
        repository.upsertSession(SESSION_ID, OWNER, "Title", "CHAT", Instant.now());

        assertThat(service.cancelRun(SESSION_ID, OWNER)).isFalse();
        assertThat(repository.readEvents(SESSION_ID, 0)).isEmpty();
    }

    @Test
    void cancelRunRejectsSessionOwnedByAnotherUser() {
        repository.upsertSession(SESSION_ID, "intruder", "theirs", "CHAT", Instant.now());

        assertThatThrownBy(() -> service.cancelRun(SESSION_ID, OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(thrown -> assertThat(
                        ((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void reconcileOrphanedRunsTerminatesEveryUnfinishedRunOnStartup() {
        String runId = "run-stuck";
        seedRunningRun(runId, "assistant-stuck");

        service.reconcileOrphanedRuns();

        assertThat(repository.runStatus(runId)).contains("error");
        ChatSessionEvent runError = repository.readEvents(SESSION_ID, 0).stream()
                .filter(event -> event.type().equals("run_error")).findFirst().orElseThrow();
        assertThat(runError.metadata()).containsEntry("message", "Run interrupted by a server restart.");
        // A second pass is a no-op: the run already reached a terminal state.
        service.reconcileOrphanedRuns();
        assertThat(repository.readEvents(SESSION_ID, 0)).filteredOn(event -> event.type().equals("run_error"))
                .hasSize(1);
    }

    /** Seeds a session whose run is mid-flight (status running, run_started + open assistant bubble). */
    private void seedRunningRun(String runId, String assistantMessageId) {
        repository.upsertSession(SESSION_ID, OWNER, "Title", "CHAT", Instant.now());
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            repository.insertRun(runId, SESSION_ID, "CHAT", "queued", now);
            repository.updateRunStatus(runId, "running", null, null);
            long s1 = repository.nextSeq(SESSION_ID, now);
            repository.insertEvent(SESSION_ID, runId, null, s1, "run_started", null, null, java.util.Map.of(), now);
            repository.insertMessage(assistantMessageId, SESSION_ID, runId, "assistant", "partial", "streaming", now);
            long s2 = repository.nextSeq(SESSION_ID, now);
            repository.insertEvent(SESSION_ID, runId, assistantMessageId, s2, "assistant_message_started",
                    "assistant", "", java.util.Map.of(), now);
        });
    }

    private static ChatSessionMessageRequest request(String content) {
        return new ChatSessionMessageRequest(content, "CHAT", "Title", List.of(), "model-x", "low", null);
    }
}
