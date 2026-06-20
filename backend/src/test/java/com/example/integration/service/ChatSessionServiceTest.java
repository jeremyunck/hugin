package com.example.integration.service;

import com.example.agent.AgentRunRegistry;
import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.integration.controller.ChatSessionMessageRequest;
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

class ChatSessionServiceTest {

    private static final String OWNER = "owner";
    private static final String SESSION_ID = "session-1";

    private ChatSessionRepository repository;
    private AgentService agentService;
    private ChatSessionService service;
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
        AgentRunRegistry runRegistry = mock(AgentRunRegistry.class);

        // Run the agent task inline so createMessage drives the whole run synchronously.
        ExecutorService inlineExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(inlineExecutor).execute(any(Runnable.class));

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        service = new ChatSessionService(
                repository, broker, agentService, inlineExecutor, runRegistry, transactionTemplate);
    }

    @Test
    void createMessagePersistsOrderedRunLifecycleEvents() {
        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onContent("Hel");
            listener.onContent("lo");
            listener.onToolCall("read_file", "{}");
            listener.onToolResult("read_file", "ok");
            return null;
        }).when(agentService).chatStream(any(), any(), any());

        ChatSessionMessageAcceptance accepted = service.createMessage(SESSION_ID, OWNER, request("Hi"));

        List<ChatSessionEvent> events = repository.readEvents(SESSION_ID, 0);
        assertThat(events).extracting(ChatSessionEvent::type).containsExactly(
                "user_message_created",
                "run_started",
                "assistant_message_started",
                "assistant_token",
                "assistant_token",
                "tool_call_started",
                "tool_call_completed",
                "assistant_message_completed",
                "run_completed");

        // Sequence numbers are gap-free and the acceptance points at the user message seq.
        assertThat(events).extracting(ChatSessionEvent::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(accepted.lastSeq()).isEqualTo(1L);

        // The completed assistant message holds the concatenated streamed tokens.
        assertThat(events.get(7).content()).isEqualTo("Hello");

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
    void createMessageRejectsSessionOwnedByAnotherUser() {
        repository.upsertSession(SESSION_ID, "intruder", "theirs", "CHAT", Instant.now());

        assertThatThrownBy(() -> service.createMessage(SESSION_ID, OWNER, request("Hi")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(thrown -> assertThat(
                        ((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(404));

        // The rejected request must not write any events for the foreign session.
        assertThat(repository.readEvents(SESSION_ID, 0)).isEmpty();
    }

    private static ChatSessionMessageRequest request(String content) {
        return new ChatSessionMessageRequest(content, "CHAT", "Title", List.of(), "model-x", "low", null);
    }
}
