package com.example.agent;

import com.example.agent.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the short-term conversation memory over the real {@link InMemoryConversationStore}:
 * recall/record, per-session isolation, sliding-window trimming, statelessness without a session
 * id, and TTL eviction of idle sessions.
 */
class ConversationMemoryServiceTest {

    private static ConversationMemoryService service(int maxMessages, Duration ttl) {
        var props = new ConversationMemoryProperties(true, maxMessages, ttl, "./conversation-memory.json");
        return new ConversationMemoryService(new InMemoryConversationStore(props), props);
    }

    @Test
    void recordsAndReplaysTurnsForASession() {
        var service = service(20, Duration.ofHours(1));
        service.record("s1", "hello", "hi there");

        List<ChatMessage> history = service.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(0).content()).isEqualTo("hello");
        assertThat(history.get(1).role()).isEqualTo("assistant");
        assertThat(history.get(1).content()).isEqualTo("hi there");
    }

    @Test
    void keepsSessionsIsolated() {
        var service = service(20, Duration.ofHours(1));
        service.record("a", "from a", "answer a");
        service.record("b", "from b", "answer b");

        assertThat(service.history("a")).extracting(ChatMessage::content)
                .containsExactly("from a", "answer a");
        assertThat(service.history("b")).extracting(ChatMessage::content)
                .containsExactly("from b", "answer b");
    }

    @Test
    void trimsToSlidingWindow() {
        var service = service(4, Duration.ofHours(1)); // keep last 4 messages = 2 turns
        service.record("s", "q1", "a1");
        service.record("s", "q2", "a2");
        service.record("s", "q3", "a3");

        assertThat(service.history("s")).extracting(ChatMessage::content)
                .containsExactly("q2", "a2", "q3", "a3");
    }

    @Test
    void isStatelessWithoutASessionId() {
        var service = service(20, Duration.ofHours(1));
        service.record(null, "hello", "hi");
        service.record("  ", "hello", "hi");

        assertThat(service.history(null)).isEmpty();
        assertThat(service.history("missing")).isEmpty();
    }

    @Test
    void doesNotRecordBlankAnswers() {
        var service = service(20, Duration.ofHours(1));
        service.record("s", "hello", "");
        service.record("s", "hello", null);

        assertThat(service.history("s")).isEmpty();
    }

    @Test
    void evictsExpiredSessions() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var props = new ConversationMemoryProperties(true, 20, Duration.ofMinutes(30), "./conversation-memory.json");
        var service = new ConversationMemoryService(new InMemoryConversationStore(props, clock), props);

        service.record("s", "hello", "hi");
        assertThat(service.history("s")).hasSize(2);

        clock.advance(Duration.ofMinutes(31));
        assertThat(service.history("s")).isEmpty();
    }

    @Test
    void concurrentRecordsDoNotLoseTurns() throws InterruptedException {
        int threads = 8;
        int perThread = 50;
        int total = threads * perThread;
        // Window large enough to retain every turn, so a lost turn shows up as a missing message.
        var service = service(total * 2, Duration.ofHours(1));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        service.record("shared", "q-" + id + "-" + i, "a-" + id + "-" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // Each record appends two messages; with an atomic append none are lost under contention.
        assertThat(service.history("shared")).hasSize(total * 2);
    }

    @Test
    void recordsFullTurnTranscriptWithTools() {
        var service = service(20, Duration.ofHours(1));
        service.recordMessages("s1", List.of(
                ChatMessage.user("hello"),
                ChatMessage.assistantWithToolCalls(List.of(
                        new com.example.agent.model.ToolCall(
                                "call_1",
                                "function",
                                new com.example.agent.model.ToolCall.FunctionCall("read_file", "{\"path\":\"README.md\"}")))),
                ChatMessage.tool("call_1", "file contents"),
                ChatMessage.assistant("done")));

        List<ChatMessage> history = service.history("s1");
        assertThat(history).hasSize(4);
        assertThat(history.get(1).toolCalls()).hasSize(1);
        assertThat(history.get(2).role()).isEqualTo("tool");
        assertThat(history.get(3).content()).isEqualTo("done");
    }

    @Test
    void leavesSessionsAliveWhenTtlIsDisabled() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var props = new ConversationMemoryProperties(true, 20, null, "./conversation-memory.json");
        var service = new ConversationMemoryService(new InMemoryConversationStore(props, clock), props);

        service.record("s", "hello", "hi");
        clock.advance(Duration.ofDays(30));

        assertThat(service.history("s")).extracting(ChatMessage::content)
                .containsExactly("hello", "hi");
    }

    /** Hand-advanced clock so TTL eviction can be tested without wall-clock sleeps. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            now = now.plus(delta);
        }

        @Override public Instant instant() {
            return now;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
