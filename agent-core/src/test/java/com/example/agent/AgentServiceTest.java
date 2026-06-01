package com.example.agent;

import com.example.agent.model.*;
import com.example.agent.prompts.Prompts;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.LocalToolRegistry;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentService}.
 *
 * <p>Verifies the agent loop iteration logic, tool-call routing, error recovery,
 * and edge cases using a mocked {@link McpToolProvider} and {@link OpenAiClient}.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    private static final String MODEL = "test-model";
    private static final String DEFAULT_MODEL = "default-model";
    private static final String PROMPT = "What time is it?";
    private static final String SESSION_ID = "session-1";
    private static final int MAX_ITERATIONS = 10;
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(1);

    @Mock
    private OpenAiClient llmClient;

    @Mock
    private McpToolProvider toolProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentService agentService;

    private static LocalToolRegistry registry(LocalTool... tools) {
        return new LocalToolRegistry(List.of(tools),
                new LocalToolProperties(true, ".", Duration.ofSeconds(30), 30_000, List.of()));
    }

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static WorkspaceRegistry defaultRegistry() {
        var props = new LocalToolProperties(true, ".", Duration.ofSeconds(30), 30_000, List.of());
        return new WorkspaceRegistry(new Workspace(props));
    }

    @Test
    void shouldCallToolAndProduceFinalAnswer() {
        // Given: one tool available, model returns tool call then final answer
        var availableTool = new AvailableTool("get_time", "Get the current time",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("get_time"), anyMap())).thenReturn("12:00");

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent("The time is 12:00."));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then
        assertThat(result.response()).contains("The time is 12:00");
        verify(toolProvider, times(1)).callTool("server1", "get_time", Map.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesReasoningContentAcrossToolLoops() {
        // Given: DeepSeek-style reasoning content alongside a tool call
        var availableTool = new AvailableTool("get_time", "Get the current time",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("get_time"), anyMap())).thenReturn("12:00");

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}", "I should check the time first."))
                .thenReturn(responseWithContent("The time is 12:00."));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: the assistant turn sent back to the model still includes reasoning_content
        assertThat(result.response()).contains("The time is 12:00");
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chat(eq(MODEL), captor.capture(), anyList());
        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1);
        assertThat(secondCallMessages).anySatisfy(msg -> {
            assertThat(msg.role()).isEqualTo("assistant");
            assertThat(msg.reasoningContent()).isEqualTo("I should check the time first.");
            assertThat(msg.toolCalls()).isNotNull();
        });
    }

    @Test
    void shouldStopAfterMaxIterations() {
        // Given: tool always returns a result, model keeps requesting tool calls
        var availableTool = new AvailableTool("always_call", "Always calls",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("always_call"), anyMap())).thenReturn("done");

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_x", "always_call", "{}"));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: should hit the max-iterations guardrail
        assertThat(result.response()).contains("maximum number of tool-call iterations");
        verify(toolProvider, times(MAX_ITERATIONS))
                .callTool(eq("server1"), eq("always_call"), anyMap());
    }

    @Test
    void shouldHandleToolCallFailureGracefully() {
        // Given: tool throws, model retries then answers
        var availableTool = new AvailableTool("flaky_tool", "Might fail",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("flaky_tool"), anyMap()))
                .thenThrow(new RuntimeException("Something went wrong"));

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "flaky_tool", "{}"))
                .thenReturn(responseWithContent("I tried but failed."));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: error message is fed back to the model, then final answer returned
        assertThat(result.response()).contains("I tried but failed.");
        verify(toolProvider, times(1)).callTool(eq("server1"), eq("flaky_tool"), anyMap());
    }

    @Test
    void shouldHandleUnknownToolGracefully() {
        // Given: model calls a tool that doesn't exist on any server
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "nonexistent_tool", "{}"))
                .thenReturn(responseWithContent("I cannot find that tool."));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then
        assertThat(result.response()).contains("I cannot find that tool.");
        verify(toolProvider, never()).callTool(anyString(), anyString(), anyMap());
    }

    @Test
    void shouldHandleToolCallsWhenFinishReasonIsNotToolCalls() {
        // Given: finish_reason is "stop" but tool_calls are present (edge case)
        var availableTool = new AvailableTool("my_tool", "Some tool",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("my_tool"), anyMap())).thenReturn("result");

        var chatResponse = new ChatResponse("id", List.of(
                new ChatResponse.Choice(0,
                        ChatMessage.assistantWithToolCalls(
                                List.of(new ToolCall("call_1", "function",
                                        new ToolCall.FunctionCall("my_tool", "{}")))),
                        "stop")
        ));

        var finalResponse = responseWithContent("Final answer.");

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(chatResponse)
                .thenReturn(finalResponse);

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: tool call should still execute despite "stop" finish_reason
        assertThat(result.response()).contains("Final answer.");
        verify(toolProvider, times(1)).callTool("server1", "my_tool", Map.of());
    }

    @Test
    void shouldTimeoutBeforeMaxIterations() {
        // Given: a very short timeout and a tool call that blocks
        var shortTimeoutService = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, SHORT_TIMEOUT, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        var availableTool = new AvailableTool("slow_tool", "Slow tool",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("slow_tool"), anyMap())).thenAnswer(invocation -> {
            Thread.sleep(100); // exceed the 1ms timeout
            return "done";
        });

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "slow_tool", "{}"));

        // When
        AgentResponse result = shortTimeoutService.chat(new AgentRequest(PROMPT, MODEL));

        // Then
        assertThat(result.response()).contains("timed out");
    }

    @Test
    void handlesEmptyChoicesGracefully() {
        // Given: the provider returns a body with no choices (e.g. an upstream hiccup)
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(new ChatResponse("id", List.of()));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: the loop degrades to a graceful answer instead of throwing
        assertThat(result.response()).contains("empty response");
    }

    @Test
    void handlesNullChoicesGracefully() {
        // Given: a malformed response whose choices field is null
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(new ChatResponse("id", null));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then
        assertThat(result.response()).contains("empty response");
    }

    @Test
    void handlesBlankFinalAnswerGracefully() {
        // Given: the model finishes with neither content nor tool calls
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent(""));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: a clear placeholder is returned rather than null/blank
        assertThat(result.response()).contains("without producing a text answer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNudgeModelWhenEmptyResponseFollowsToolCalls() {
        // Given: some models (e.g. deepseek streaming) return an empty message after tool results
        // instead of immediately providing a text answer. The agent should nudge once and retry.
        var availableTool = new AvailableTool("get_time", "Get the current time",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("get_time"), anyMap())).thenReturn("12:00");

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent(""))          // empty after tool results
                .thenReturn(responseWithContent("The time is 12:00."));  // answer after nudge

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: the model was nudged and the final answer is returned
        assertThat(result.response()).isEqualTo("The time is 12:00.");
        verify(llmClient, times(3)).chat(anyString(), anyList(), anyList());

        // The nudge message ("Please provide your answer.") should appear in the message history
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(3)).chat(eq(MODEL), captor.capture(), anyList());
        List<ChatMessage> thirdCallMessages = captor.getAllValues().get(2);
        assertThat(thirdCallMessages).anySatisfy(msg -> {
            assertThat(msg.role()).isEqualTo("user");
            assertThat(msg.content()).contains("Please provide your answer");
        });
    }

    @Test
    void shouldNotNudgeWhenEmptyResponseWithNoToolCalls() {
        // Given: empty response on the very first turn (no tool calls at all) — no nudge expected
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent(""));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: fallback message returned, only one LLM call (no nudge)
        assertThat(result.response()).contains("without producing a text answer");
        verify(llmClient, times(1)).chat(anyString(), anyList(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNudgeOnlyOnceEvenIfModelStillReturnsEmpty() {
        // Given: model keeps returning empty after tool calls even after a nudge
        var availableTool = new AvailableTool("get_time", "Get the current time",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("get_time"), anyMap())).thenReturn("12:00");

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent(""))   // empty after tool results
                .thenReturn(responseWithContent(""));  // still empty after nudge

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: fallback returned after one nudge attempt; exactly 3 LLM calls (no infinite loop)
        assertThat(result.response()).contains("without producing a text answer");
        verify(llmClient, times(3)).chat(anyString(), anyList(), anyList());
    }

    @Test
    void shouldReturnDirectAnswerWithNoTools() {
        // Given: no tools available, model responds directly
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Hello there!"));

        // When
        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then
        assertThat(result.response()).isEqualTo("Hello there!");
        verify(toolProvider, never()).callTool(anyString(), anyString(), anyMap());
    }

    @Test
    void shouldRouteToBuiltinLocalToolWithoutMcpServer() {
        // Given: a built-in local tool and no MCP servers
        var recordingTool = new RecordingLocalTool("local_echo", "echoed: ok");
        var localService = new AgentService(
                llmClient, toolProvider, registry(recordingTool), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "local_echo", "{\"value\":\"ok\"}"))
                .thenReturn(responseWithContent("Done."));

        // When
        AgentResponse result = localService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: the local tool ran in-process and MCP routing was never attempted
        assertThat(result.response()).contains("Done.");
        assertThat(recordingTool.lastArgs).containsEntry("value", "ok");
        verify(toolProvider, never()).callTool(anyString(), anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPrependSystemPromptWhenToolsAvailable() {
        // Given: a tool is available
        var availableTool = new AvailableTool("get_time", "Get the current time",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("12:00"));

        // When
        agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: first message is the tool system prompt
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue().get(0).role()).isEqualTo("system");
        assertThat(captor.getValue().get(0).content()).isEqualTo(Prompts.TOOL_USE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotPrependSystemPromptWhenNoToolsAvailable() {
        // Given: no tools at all
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Hi!"));

        // When
        agentService.chat(new AgentRequest(PROMPT, MODEL));

        // Then: conversation starts directly with the user message
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue().get(0).role()).isEqualTo("user");
    }

    @Test
    void chatStreamDeliversTokensAndFinalAnswer() {
        // Given: no tools; the streaming call emits two text fragments
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onDelta = invocation.getArgument(3);
                    onDelta.accept("Hel");
                    onDelta.accept("lo!");
                    return responseWithContent("Hello!");
                });

        var listener = new RecordingListener();

        // When
        AgentResponse result = agentService.chatStream(new AgentRequest(PROMPT, MODEL), listener);

        // Then: tokens streamed in order and the final answer matches
        assertThat(listener.tokens).containsExactly("Hel", "lo!");
        assertThat(result.response()).isEqualTo("Hello!");
        verify(llmClient, never()).chat(anyString(), anyList(), anyList());
    }

    @Test
    void chatStreamReportsToolCallEvents() {
        // Given: a tool, then a streaming tool call followed by a final answer
        var availableTool = new AvailableTool("get_time", "Get the current time",
                Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(eq("server1"), eq("get_time"), anyMap())).thenReturn("12:00");

        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent("The time is 12:00."));

        var listener = new RecordingListener();

        // When
        AgentResponse result = agentService.chatStream(new AgentRequest(PROMPT, MODEL), listener);

        // Then: tool-call lifecycle is reported and the loop produces the final answer
        assertThat(listener.toolCalls).containsExactly("get_time");
        assertThat(listener.toolResults).containsExactly("12:00");
        assertThat(result.response()).contains("The time is 12:00");
        verify(toolProvider, times(1)).callTool("server1", "get_time", Map.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRecalledMemoryAndStoresFinalAnswer() {
        // Given: a memory service that recalls one past exchange
        MemoryService memory = mock(MemoryService.class);
        when(memory.recall(PROMPT)).thenReturn(List.of(new MemoryStore.ScoredMemory(
                new MemoryRecord("1", "User: hi\nAssistant: hello", new float[]{0.1f, 0.2f},
                        java.time.Instant.now()),
                0.9)));
        var service = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.of(memory), Optional.empty(), Optional.empty(), Optional.empty());
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Final answer."));

        // When
        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        // Then: recalled memory is injected as a system message before the user prompt,
        // and the finished exchange is stored back.
        assertThat(result.response()).isEqualTo("Final answer.");
        verify(memory).recall(PROMPT);
        verify(memory).remember(PROMPT, "Final answer.");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        List<ChatMessage> sent = captor.getValue();
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(0).content()).contains("User: hi\nAssistant: hello");
        assertThat(sent.get(1).role()).isEqualTo("user");
        assertThat(sent.get(1).content()).isEqualTo(PROMPT);
    }

    @Test
    void doesNotInjectMemoryMessageWhenNothingRecalled() {
        // Given: memory enabled but nothing relevant recalled
        MemoryService memory = mock(MemoryService.class);
        when(memory.recall(PROMPT)).thenReturn(List.of());
        var service = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.of(memory), Optional.empty(), Optional.empty(), Optional.empty());
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Hi!"));

        // When
        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        // Then: conversation starts directly with the user message, answer still stored
        assertThat(result.response()).isEqualTo("Hi!");
        verify(memory).remember(PROMPT, "Hi!");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaysSessionHistoryBeforePromptAndRecordsTurn() {
        // Given: short-term memory holding one prior exchange for the session
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.history(SESSION_ID)).thenReturn(List.of(
                ChatMessage.user("My name is Ada."),
                ChatMessage.assistant("Nice to meet you, Ada.")));
        var service = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.empty(), Optional.of(conversation), Optional.empty(), Optional.empty());
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Your name is Ada."));

        // When
        AgentResponse result = service.chat(new AgentRequest("What is my name?", MODEL, SESSION_ID));

        // Then: prior turns are replayed before the current prompt, and the new turn is recorded
        assertThat(result.response()).isEqualTo("Your name is Ada.");
        verify(conversation).record(SESSION_ID, "What is my name?", "Your name is Ada.");

        // The captured list is the loop's live message buffer; assert the leading messages, which
        // are the replayed history followed by the current prompt (the assistant reply is appended
        // by the loop after the call returns).
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        List<ChatMessage> sent = captor.getValue();
        assertThat(sent.get(0).content()).isEqualTo("My name is Ada.");
        assertThat(sent.get(1).content()).isEqualTo("Nice to meet you, Ada.");
        assertThat(sent.get(2).role()).isEqualTo("user");
        assertThat(sent.get(2).content()).isEqualTo("What is my name?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsStartupAnnouncementAsSystemMessage(@TempDir Path tmp) throws Exception {
        // Given: an announcement written before the restart
        Path file = tmp.resolve("announcement");
        Files.writeString(file, "Self-update completed. Now running version: 1.2.3");
        var svc = new StartupAnnouncementService(file.toString());
        svc.load();

        var service = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(svc));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Done."));

        // When
        service.chat(new AgentRequest(PROMPT, MODEL));

        // Then: a system message containing the announcement is included in the chat request
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue()).anySatisfy(msg -> {
            assertThat(msg.role()).isEqualTo("system");
            assertThat(msg.content()).contains("1.2.3");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void announcementIsConsumedAfterFirstRequest(@TempDir Path tmp) throws Exception {
        // Given: a pending announcement
        Path file = tmp.resolve("announcement");
        Files.writeString(file, "version 2.0");
        var svc = new StartupAnnouncementService(file.toString());
        svc.load();

        var service = new AgentService(
                llmClient, toolProvider, registry(), objectMapper, FIVE_MINUTES, DEFAULT_MODEL, MAX_ITERATIONS,
                defaultRegistry(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(svc));
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Done."));

        // When: two consecutive requests
        service.chat(new AgentRequest(PROMPT, MODEL));
        service.chat(new AgentRequest(PROMPT, MODEL));

        // Then: first call carries the announcement, second does not
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chat(eq(MODEL), captor.capture(), anyList());
        List<List<ChatMessage>> calls = captor.getAllValues();
        assertThat(calls.get(0)).anySatisfy(msg -> assertThat(msg.content()).contains("version 2.0"));
        assertThat(calls.get(1)).noneMatch(msg -> msg.content() != null && msg.content().contains("version 2.0"));
    }

    // ---- helpers ----

    private static final class RecordingListener implements AgentStreamListener {
        private final List<String> tokens = new java.util.ArrayList<>();
        private final List<String> toolCalls = new java.util.ArrayList<>();
        private final List<String> toolResults = new java.util.ArrayList<>();

        @Override public void onContent(String delta) {
            tokens.add(delta);
        }

        @Override public void onToolCall(String toolName, String arguments) {
            toolCalls.add(toolName);
        }

        @Override public void onToolResult(String toolName, String result) {
            toolResults.add(result);
        }
    }

    private static final class RecordingLocalTool implements LocalTool {
        private final String name;
        private final String result;
        private Map<String, Object> lastArgs;

        RecordingLocalTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override public String name() {
            return name;
        }

        @Override public String description() {
            return "test tool";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override public String execute(Map<String, Object> arguments) {
            this.lastArgs = arguments;
            return result;
        }
    }

    private static ChatResponse responseWithContent(String content) {
        return new ChatResponse("id", List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant(content), "stop")));
    }

    private static ChatResponse responseWithToolCall(String id, String name, String args) {
        return responseWithToolCall(id, name, args, null);
    }

    private static ChatResponse responseWithToolCall(String id, String name, String args, String reasoningContent) {
        var toolCall = new ToolCall(id, "function", new ToolCall.FunctionCall(name, args));
        return new ChatResponse("id", List.of(
                new ChatResponse.Choice(0,
                        ChatMessage.assistantWithToolCalls(List.of(toolCall), reasoningContent),
                        "tool_calls")));
    }
}
