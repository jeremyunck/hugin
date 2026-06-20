package com.example.integration.service;

import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSessionRepositoryTest {

    private static final String OWNER = "owner";
    private static final String SESSION_ID = "session-1";

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private ChatSessionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:chat-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.update("insert into app_users (username, password_hash) values (?, ?)", OWNER, "hash");
        repository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
        repository.upsertSession(SESSION_ID, OWNER, "Title", "CHAT", Instant.now());
    }

    @Test
    void nextSeqAllocatesGapFreeSequenceNumbers() {
        List<Long> seqs = LongStream.range(0, 5)
                .mapToObj(ignored -> repository.nextSeq(SESSION_ID, Instant.now()))
                .toList();

        assertThat(seqs).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void nextSeqThrowsForMissingSession() {
        assertThatThrownBy(() -> repository.nextSeq("missing", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentNextSeqDoesNotReuseSequenceNumbers() throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<Long> allocated = ConcurrentHashMap.newKeySet();
        try {
            List<Callable<Void>> tasks = IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(ignored -> () -> {
                        barrier.await();
                        for (int i = 0; i < perThread; i++) {
                            // Each allocation runs in its own transaction so the FOR UPDATE row lock
                            // taken by nextSeq is held across the read-then-update, just like in
                            // ChatSessionService. Without that lock concurrent callers would collide.
                            long seq = transactionTemplate.execute(status ->
                                    repository.nextSeq(SESSION_ID, Instant.now()));
                            allocated.add(seq);
                        }
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        int total = threads * perThread;
        assertThat(allocated).hasSize(total);
        assertThat(allocated).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, total).boxed().toList());
    }

    @Test
    void readEventsReturnsOnlyEventsAfterSeqInOrder() {
        insertEvent(1, "type-a");
        insertEvent(2, "type-b");
        insertEvent(3, "type-c");

        List<ChatSessionEvent> all = repository.readEvents(SESSION_ID, 0);
        assertThat(all).extracting(ChatSessionEvent::seq).containsExactly(1L, 2L, 3L);

        List<ChatSessionEvent> tail = repository.readEvents(SESSION_ID, 1);
        assertThat(tail).extracting(ChatSessionEvent::type).containsExactly("type-b", "type-c");
    }

    @Test
    void insertEventRoundTripsMetadata() {
        repository.insertEvent(SESSION_ID, "run-1", "message-1", 1, "tool_call_started", "assistant",
                "body", Map.of("name", "read_file", "args", "{}"), Instant.now());

        ChatSessionEvent stored = repository.readEvents(SESSION_ID, 0).get(0);
        assertThat(stored.runId()).isEqualTo("run-1");
        assertThat(stored.messageId()).isEqualTo("message-1");
        assertThat(stored.content()).isEqualTo("body");
        assertThat(stored.metadata()).containsEntry("name", "read_file").containsEntry("args", "{}");
    }

    @Test
    void toolActivityEventsArePersistedAndReplayedInOrder() {
        // Mirrors ChatSessionService.appendActivity for the activity panel: tool call lifecycle
        // events carry no message id but must round-trip their metadata and ordering.
        repository.insertEvent(SESSION_ID, "run-1", null, 1, "tool_call_started", null,
                null, Map.of("name", "read_file", "args", "{\"path\":\"a.txt\"}"), Instant.now());
        repository.insertEvent(SESSION_ID, "run-1", null, 2, "tool_call_completed", null,
                null, Map.of("name", "read_file", "result", "contents"), Instant.now());

        List<ChatSessionEvent> events = repository.readEvents(SESSION_ID, 0);

        assertThat(events).extracting(ChatSessionEvent::type)
                .containsExactly("tool_call_started", "tool_call_completed");
        assertThat(events.get(0).metadata())
                .containsEntry("name", "read_file")
                .containsEntry("args", "{\"path\":\"a.txt\"}");
        assertThat(events.get(1).metadata())
                .containsEntry("name", "read_file")
                .containsEntry("result", "contents");
        // Tool activity has no associated message, so it must not leak into the replayed transcript.
        assertThat(repository.buildPriorMessages(SESSION_ID, 3)).isEmpty();
    }

    @Test
    void appendMessageContentAccumulatesTokens() {
        repository.insertMessage("message-1", SESSION_ID, "run-1", "assistant", "", "streaming", Instant.now());
        repository.appendMessageContent("message-1", "Hel", Instant.now());
        repository.appendMessageContent("message-1", "lo", Instant.now());

        assertThat(repository.readMessageContent("message-1")).contains("Hello");
    }

    @Test
    void buildPriorMessagesReplaysUserAndAssistantTranscript() {
        // user turn
        repository.insertEvent(SESSION_ID, "run-1", "user-1", 1, "user_message_created", "user",
                "What is the weather?", Map.of(), Instant.now());
        // assistant turn streamed token-by-token
        repository.insertEvent(SESSION_ID, "run-1", "assistant-1", 2, "assistant_message_started", "assistant",
                "", Map.of(), Instant.now());
        repository.insertEvent(SESSION_ID, "run-1", "assistant-1", 3, "assistant_token", "assistant",
                "It is ", Map.of(), Instant.now());
        repository.insertEvent(SESSION_ID, "run-1", "assistant-1", 4, "assistant_token", "assistant",
                "sunny.", Map.of(), Instant.now());
        repository.insertEvent(SESSION_ID, "run-1", "assistant-1", 5, "assistant_message_completed", "assistant",
                "It is sunny.", Map.of(), Instant.now());
        // a run-level event without a message id should be ignored by the replay
        repository.insertEvent(SESSION_ID, "run-1", null, 6, "run_completed", null,
                null, Map.of(), Instant.now());

        List<ChatMessage> transcript = repository.buildPriorMessages(SESSION_ID, 7);

        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0).role()).isEqualTo("user");
        assertThat(transcript.get(0).content()).isEqualTo("What is the weather?");
        assertThat(transcript.get(1).role()).isEqualTo("assistant");
        assertThat(transcript.get(1).content()).isEqualTo("It is sunny.");
    }

    @Test
    void buildPriorMessagesExcludesEventsAtOrAfterCutoff() {
        repository.insertEvent(SESSION_ID, "run-1", "user-1", 1, "user_message_created", "user",
                "first", Map.of(), Instant.now());
        repository.insertEvent(SESSION_ID, "run-2", "user-2", 2, "user_message_created", "user",
                "current turn", Map.of(), Instant.now());

        // Cutoff at seq 2 means the in-flight user turn (seq 2) is not replayed back to the agent.
        List<ChatMessage> transcript = repository.buildPriorMessages(SESSION_ID, 2);

        assertThat(transcript).extracting(ChatMessage::content).containsExactly("first");
    }

    @Test
    void sessionOwnershipChecksAreScopedToOwner() {
        assertThat(repository.sessionExists(SESSION_ID)).isTrue();
        assertThat(repository.sessionExistsForOwner(SESSION_ID, OWNER)).isTrue();
        assertThat(repository.sessionExistsForOwner(SESSION_ID, "intruder")).isFalse();
        assertThat(repository.sessionExists("missing")).isFalse();
    }

    private void insertEvent(long seq, String type) {
        repository.insertEvent(SESSION_ID, "run-1", "message-1", seq, type, "assistant",
                "content-" + seq, Map.of(), Instant.now());
    }
}
