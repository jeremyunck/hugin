package com.example.agent;

import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.ChatAttachment;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatResponse;
import com.example.agent.MemoryStore;
import com.example.agent.model.MemoryRecord;
import com.example.agent.model.ToolCall;
import com.example.agent.prompts.Prompts;
import com.example.agent.tool.JustInTimeToolRegistry;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.LocalToolRegistry;
import com.example.agent.tool.ToolContext;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static LocalToolRegistry registry(LocalTool... tools) {
        return new LocalToolRegistry(List.of(tools),
                new LocalToolProperties(true, ".", Duration.ofSeconds(30), 30_000, List.of()));
    }

    private JustInTimeToolRegistry jitRegistry(Path root) {
        var props = new LocalToolProperties(true, root.toString(), Duration.ofSeconds(30), 30_000, List.of());
        return new JustInTimeToolRegistry(props, objectMapper);
    }

    private static WorkspaceRegistry defaultRegistry() {
        var props = new LocalToolProperties(true, ".", Duration.ofSeconds(30), 30_000, List.of());
        return new WorkspaceRegistry(new Workspace(props));
    }

    @Test
    void shouldCallToolAndProduceFinalAnswer() {
        var tool = new RecordingLocalTool("get_time", "12:00");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent("The time is 12:00."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("The time is 12:00");
        assertThat(tool.callCount).isEqualTo(1);
        assertThat(tool.lastArgs).isEqualTo(Map.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesReasoningContentAcrossToolLoops() {
        var tool = new RecordingLocalTool("get_time", "12:00");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}", "I should check the time first."))
                .thenReturn(responseWithContent("The time is 12:00."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("The time is 12:00");
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chat(eq(MODEL), captor.capture(), anyList());
        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1);
        assertThat(secondCallMessages).anySatisfy(msg -> {
            assertThat(msg.role()).isEqualTo("assistant");
            assertThat(msg.content()).isNotNull();
            assertThat(msg.reasoningContent()).isEqualTo("I should check the time first.");
            assertThat(msg.toolCalls()).isNotNull();
        });
    }

    @Test
    void shouldStopAfterMaxIterations() {
        var tool = new RecordingLocalTool("always_call", "done");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_x", "always_call", "{}"));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("maximum number of tool-call iterations");
        assertThat(tool.callCount).isEqualTo(MAX_ITERATIONS);
    }

    @Test
    void shouldHonorPerRequestToolCallCapBelowDefault() {
        var tool = new RecordingLocalTool("always_call", "done");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_x", "always_call", "{}"));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL), "owner", 3);

        assertThat(result.response()).contains("maximum number of tool-call iterations");
        assertThat(tool.callCount).isEqualTo(3);
    }

    @Test
    void shouldFallBackToDefaultCapWhenRequestedToolCallsAreNonPositive() {
        var tool = new RecordingLocalTool("always_call", "done");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_x", "always_call", "{}"));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL), "owner", 0);

        assertThat(tool.callCount).isEqualTo(MAX_ITERATIONS);
    }

    @Test
    void shouldHandleToolCallFailureGracefully() {
        var tool = new RecordingLocalTool("flaky_tool", "ignored");
        tool.throwMessage = "Something went wrong";
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "flaky_tool", "{}"))
                .thenReturn(responseWithContent("I tried but failed."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("I tried but failed.");
        assertThat(tool.callCount).isEqualTo(1);
    }

    @Test
    void shouldHandleUnknownToolGracefully() {
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "nonexistent_tool", "{}"))
                .thenReturn(responseWithContent("I cannot find that tool."));

        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("I cannot find that tool.");
    }

    @Test
    void shouldHandleToolCallsWhenFinishReasonIsNotToolCalls() {
        var tool = new RecordingLocalTool("my_tool", "result");
        var service = serviceWithTools(tool);

        var chatResponse = new ChatResponse("id", List.of(
                new ChatResponse.Choice(0,
                        ChatMessage.assistantWithToolCalls(
                                List.of(new ToolCall("call_1", "function",
                                        new ToolCall.FunctionCall("my_tool", "{}")))),
                        "stop")
        ));

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(chatResponse)
                .thenReturn(responseWithContent("Final answer."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("Final answer.");
        assertThat(tool.callCount).isEqualTo(1);
    }

    @Test
    void shouldTimeoutBeforeMaxIterations() {
        var tool = new RecordingLocalTool("slow_tool", "done");
        tool.delay = Duration.ofMillis(100);
        var shortTimeoutService = new AgentService(
                llmClient,
                registry(tool),
                jitRegistry(Path.of(".")),
                objectMapper,
                SHORT_TIMEOUT,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "slow_tool", "{}"));

        AgentResponse result = shortTimeoutService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("timed out");
    }

    @Test
    void perRequestTimeoutOverrideExtendsShortServerDefault() {
        // A 1ms server default would time out before the first model call; a generous per-request
        // override (clamped up to the 30s floor) gives the run room to finish normally.
        var shortDefaultService = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                SHORT_TIMEOUT,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenReturn(responseWithContent("Final answer."));

        AgentResponse result = shortDefaultService.chatStream(
                new AgentRequest(PROMPT, MODEL), new AgentStreamListener() {}, "owner", null, null, 60);

        assertThat(result.response()).contains("Final answer.");
        assertThat(result.response()).doesNotContain("timed out");
    }

    @Test
    void handlesEmptyChoicesGracefully() {
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(new ChatResponse("id", List.of()));

        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("empty response");
    }

    @Test
    void handlesNullChoicesGracefully() {
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(new ChatResponse("id", null));

        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("empty response");
    }

    @Test
    void handlesBlankFinalAnswerGracefully() {
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent(""));

        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("without producing a text answer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNudgeModelWhenEmptyResponseFollowsToolCalls() {
        var tool = new RecordingLocalTool("get_time", "12:00");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent(""))
                .thenReturn(responseWithContent("The time is 12:00."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).isEqualTo("The time is 12:00.");
        verify(llmClient, times(3)).chat(anyString(), anyList(), anyList());

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
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent(""));

        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("without producing a text answer");
        verify(llmClient, times(1)).chat(anyString(), anyList(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNudgeOnlyOnceEvenIfModelStillReturnsEmpty() {
        var tool = new RecordingLocalTool("get_time", "12:00");
        var service = serviceWithTools(tool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent(""))
                .thenReturn(responseWithContent(""));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("without producing a text answer");
        verify(llmClient, times(3)).chat(anyString(), anyList(), anyList());
    }

    @Test
    void shouldReturnDirectAnswerWithNoTools() {
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Hello there!"));

        AgentResponse result = agentService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).isEqualTo("Hello there!");
    }

    @Test
    void shouldRoutePromptThroughDecisionModelAndSimpleModel() {
        when(llmClient.chat(eq("decision-model"), anyList(), anyList()))
                .thenReturn(responseWithContent("simple"));
        when(llmClient.chat(eq("simple-model"), anyList(), anyList()))
                .thenReturn(responseWithContent("Simple answer."));

        AgentResponse result = agentService.chat(new AgentRequest(
                PROMPT, "decision-model", "complex-model", "simple-model"));

        assertThat(result.response()).isEqualTo("Simple answer.");
        verify(llmClient).chat(eq("decision-model"), anyList(), anyList());
        verify(llmClient).chat(eq("simple-model"), anyList(), anyList());
        verify(llmClient, times(0)).chat(eq("complex-model"), anyList(), anyList());
    }

    @Test
    void shouldReuseCachedRoutingDecisionForIdenticalPrompt() {
        when(llmClient.chat(eq("decision-model"), anyList(), anyList()))
                .thenReturn(responseWithContent("simple"));
        when(llmClient.chat(eq("simple-model"), anyList(), anyList()))
                .thenReturn(responseWithContent("Simple answer."));

        var request = new AgentRequest(PROMPT, "decision-model", "complex-model", "simple-model");
        agentService.chat(request);
        agentService.chat(request);

        // The second identical request hits the routing cache: one decision call, two answers.
        verify(llmClient, times(1)).chat(eq("decision-model"), anyList(), anyList());
        verify(llmClient, times(2)).chat(eq("simple-model"), anyList(), anyList());
    }

    @Test
    void shouldRouteToBuiltinLocalToolWithoutRemoteToolProvider() {
        var recordingTool = new RecordingLocalTool("local_echo", "echoed: ok");
        var localService = serviceWithTools(recordingTool);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "local_echo", "{\"value\":\"ok\"}"))
                .thenReturn(responseWithContent("Done."));

        AgentResponse result = localService.chat(new AgentRequest(PROMPT, MODEL));

        assertThat(result.response()).contains("Done.");
        assertThat(recordingTool.lastArgs).containsEntry("value", "ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPrependSystemPromptWhenToolsAvailable() {
        var service = serviceWithTools(new RecordingLocalTool("get_time", "12:00"));

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("12:00"));

        service.chat(new AgentRequest(PROMPT, MODEL));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue().get(0).role()).isEqualTo("system");
        assertThat(captor.getValue().get(0).content()).isEqualTo(Prompts.TOOL_USE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsWorkspaceSkillPromptWhenSkillsExist(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("skills/explore-github-repository"));
        Files.writeString(tmp.resolve("skills/explore-github-repository/SKILL.md"), """
                ---
                name: explore-github-repository
                description: Use when starting work in an unfamiliar repository.
                ---
                """);
        var props = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(30), 30_000, List.of());
        var workspace = new Workspace(props);
        var service = new AgentService(
                llmClient,
                registry(new RecordingLocalTool("get_time", "12:00")),
                jitRegistry(tmp),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                new WorkspaceRegistry(workspace),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("12:00"));

        service.chat(new AgentRequest(PROMPT, null, MODEL, null, MODEL, MODEL, MODEL, null, null, SESSION_ID, null, null, "sandbox-1", null));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("Workspace skills are available");
            assertThat(message.content()).contains("skills/explore-github-repository/SKILL.md");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsGithubRepoContextForRepoSandbox() {
        var props = new LocalToolProperties(true, ".", Duration.ofSeconds(30), 30_000, List.of());
        var workspaceRegistry = new WorkspaceRegistry(new Workspace(props));
        workspaceRegistry.registerGithubRepo("sbx-1", "octocat/hello-world");
        var service = new AgentService(
                llmClient,
                registry(new RecordingLocalTool("get_time", "12:00")),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                workspaceRegistry,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("ok"));

        service.chat(new AgentRequest(PROMPT, MODEL, MODEL, MODEL, MODEL, null, null, null, null, "sbx-1"));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("GitHub repository octocat/hello-world");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsGithubRepoContextForNonRepoSandbox() {
        var service = serviceWithTools(new RecordingLocalTool("get_time", "12:00"));

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("ok"));

        service.chat(new AgentRequest(PROMPT, MODEL, MODEL, MODEL, MODEL, null, null, null, null, "sbx-1"));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue()).noneSatisfy(message ->
                assertThat(message.content()).contains("software engineer working in the GitHub repository"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotPrependSystemPromptWhenNoToolsAvailable() {
        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Hi!"));

        agentService.chat(new AgentRequest(PROMPT, MODEL));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue().get(0).role()).isEqualTo("user");
    }

    @Test
    void chatStreamDeliversTokensAndFinalAnswer() {
        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onDelta = invocation.getArgument(3);
                    onDelta.accept("Hel");
                    onDelta.accept("lo!");
                    return responseWithContent("Hello!");
                });

        var listener = new RecordingListener();

        AgentResponse result = agentService.chatStream(new AgentRequest(PROMPT, MODEL), listener);

        assertThat(listener.tokens).containsExactly("Hel", "lo!");
        assertThat(result.response()).isEqualTo("Hello!");
    }

    @Test
    void chatStreamReportsToolCallEvents() {
        var service = serviceWithTools(new RecordingLocalTool("get_time", "12:00"));

        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenReturn(responseWithToolCall("call_1", "get_time", "{}"))
                .thenReturn(responseWithContent("The time is 12:00."));

        var listener = new RecordingListener();

        AgentResponse result = service.chatStream(new AgentRequest(PROMPT, MODEL), listener);

        assertThat(listener.toolCalls).containsExactly("get_time");
        assertThat(listener.toolResults).containsExactly("12:00");
        assertThat(result.response()).contains("The time is 12:00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRecalledMemoryAndStoresFinalAnswer() {
        MemoryService memory = mock(MemoryService.class);
        when(memory.recall("alice", PROMPT)).thenReturn(List.of(new MemoryStore.ScoredMemory(
                new MemoryRecord("1", "User: hi\nAssistant: hello", new float[]{0.1f, 0.2f},
                        Instant.now()),
                0.9)));
        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.of(memory),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Final answer."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL), "alice");

        assertThat(result.response()).isEqualTo("Final answer.");
        verify(memory).recall("alice", PROMPT);
        verify(memory).remember("alice", PROMPT, "Final answer.");

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
        MemoryService memory = mock(MemoryService.class);
        when(memory.recall("alice", PROMPT)).thenReturn(List.of());
        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.of(memory),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Hi!"));

        AgentResponse result = service.chat(new AgentRequest(PROMPT, MODEL), "alice");

        assertThat(result.response()).isEqualTo("Hi!");
        verify(memory).remember("alice", PROMPT, "Hi!");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaysSessionHistoryBeforePromptAndRecordsTurn() {
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        String scopedSessionId = "global:session-1";
        when(conversation.history(scopedSessionId)).thenReturn(List.of(
                ChatMessage.user("My name is Ada."),
                ChatMessage.assistant("Nice to meet you, Ada.")));
        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.of(conversation),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Your name is Ada."));

        AgentResponse result = service.chat(new AgentRequest("What is my name?", MODEL, SESSION_ID));

        assertThat(result.response()).isEqualTo("Your name is Ada.");
        ArgumentCaptor<List<ChatMessage>> transcriptCaptor = ArgumentCaptor.forClass(List.class);
        verify(conversation).recordMessages(eq(scopedSessionId), transcriptCaptor.capture());
        assertThat(transcriptCaptor.getValue()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
        assertThat(transcriptCaptor.getValue().get(0).content()).isEqualTo("What is my name?");
        assertThat(transcriptCaptor.getValue().get(0).attachments()).isNull();

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
    void recordsImageAttachmentsInConversationMemory() {
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.of(conversation),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("It looks like a raven."));

        List<ChatAttachment> attachments = List.of(
                new ChatAttachment("bird.png", "image/png", "data:image/png;base64,abc123", 123L));
        AgentResponse result = service.chat(new AgentRequest(
                "What is in this image?",
                attachments,
                MODEL,
                MODEL,
                MODEL,
                MODEL,
                null,
                null,
                SESSION_ID,
                null));

        assertThat(result.response()).isEqualTo("It looks like a raven.");

        ArgumentCaptor<List<ChatMessage>> transcriptCaptor = ArgumentCaptor.forClass(List.class);
        verify(conversation).recordMessages(eq("global:" + SESSION_ID), transcriptCaptor.capture());
        assertThat(transcriptCaptor.getValue().get(0).attachments()).hasSize(1);
        assertThat(transcriptCaptor.getValue().get(0).attachments().get(0).dataUrl()).isEqualTo("data:image/png;base64,abc123");
    }

    @Test
    void clientManagedContextSkipsConversationMemoryAndThreadsChannelHistoryToTools() {
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        RecordingChannelTool channelTool = new RecordingChannelTool();
        var service = new AgentService(
                llmClient,
                registry(channelTool),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.of(conversation),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("call_1", "echo_channel", "{}"))
                .thenReturn(responseWithContent("done"));

        List<String> recent = List.of("Alice: hi", "Bob: yo");
        AgentResponse result = service.chat(new AgentRequest(
                PROMPT,
                null,
                MODEL,
                null,
                MODEL,
                MODEL,
                MODEL,
                null,
                null,
                "discord-channel-1",
                null,
                recent,
                null,
                true));

        assertThat(result.response()).isEqualTo("done");
        verify(conversation, never()).history(anyString());
        verify(conversation, never()).record(anyString(), anyString(), anyString());
        verify(conversation, never()).recordMessages(anyString(), anyList());
        assertThat(channelTool.seen).isEqualTo(recent);
    }

    @Test
    @SuppressWarnings("unchecked")
    void priorMessagesReplayBeforePromptAndStillRecordServerMemory() {
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.of(conversation),
                Optional.empty(),
                Optional.empty());

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("You asked me this before."));

        List<ChatMessage> prior = List.of(
                ChatMessage.user("Remember this."),
                ChatMessage.assistant("I will remember."));
        AgentResponse result = service.chat(new AgentRequest(
                "What did I ask you to remember?",
                null,
                MODEL,
                null,
                MODEL,
                MODEL,
                MODEL,
                null,
                null,
                SESSION_ID,
                prior,
                null,
                null,
                false));

        assertThat(result.response()).isEqualTo("You asked me this before.");
        verify(conversation, never()).history(anyString());
        verify(conversation).recordMessages(eq("global:" + SESSION_ID), anyList());

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(eq(MODEL), captor.capture(), anyList());
        assertThat(captor.getValue()).extracting(ChatMessage::content)
                .containsSequence("Remember this.", "I will remember.", "What did I ask you to remember?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsStartupAnnouncementAsSystemMessage(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("announcement");
        Files.writeString(file, "Self-update completed. Now running version: 1.2.3");
        var svc = new StartupAnnouncementService(file.toString());
        svc.load();

        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(svc));

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Done."));

        service.chat(new AgentRequest(PROMPT, MODEL));

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
        Path file = tmp.resolve("announcement");
        Files.writeString(file, "version 2.0");
        var svc = new StartupAnnouncementService(file.toString());
        svc.load();

        var service = new AgentService(
                llmClient,
                registry(),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(svc));

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithContent("Done."));

        service.chat(new AgentRequest(PROMPT, MODEL));
        service.chat(new AgentRequest(PROMPT, MODEL));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chat(eq(MODEL), captor.capture(), anyList());
        List<List<ChatMessage>> calls = captor.getAllValues();
        assertThat(calls.get(0)).anySatisfy(msg -> assertThat(msg.content()).contains("version 2.0"));
        assertThat(calls.get(1)).noneMatch(msg -> msg.content() != null && msg.content().contains("version 2.0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void capsTranscriptToContextWindowWhenToolResultsBalloon() {
        // A tool whose single result dwarfs the model's context window (~100k tokens of text).
        String huge = "x".repeat(400_000);
        var tool = new RecordingLocalTool("big_tool", huge);
        var service = serviceWithTools(tool);

        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenReturn(responseWithToolCall("call_1", "big_tool", "{}"))
                .thenReturn(responseWithContent("Done."));

        long contextLimit = 60_000L;
        AgentResponse result = service.chatStream(
                new AgentRequest(PROMPT, MODEL), new AgentStreamListener() {}, "global", contextLimit);

        assertThat(result.response()).contains("Done.");

        // The ballooning tool result is truncated in place before the next provider call, so the
        // transcript stays within the context budget instead of overflowing it (which the provider
        // would reject with HTTP 400).
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chatStream(eq(MODEL), captor.capture(), anyList(), any(), any());
        List<ChatMessage> secondCall = captor.getAllValues().get(1);
        ChatMessage toolMessage = secondCall.stream()
                .filter(m -> "tool".equals(m.role())).findFirst().orElseThrow();
        assertThat(toolMessage.content().length()).isLessThan(huge.length());
        assertThat(toolMessage.content()).contains("truncated to fit");
        assertThat(AgentService.estimateTokens(secondCall)).isLessThan((int) contextLimit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesTranscriptIntactWhenContextWindowUnknown() {
        // A null context limit (e.g. a model the catalog has no window for) disables the in-loop cap,
        // preserving the previous behaviour rather than guessing a budget.
        String big = "y".repeat(400_000);
        var tool = new RecordingLocalTool("big_tool", big);
        var service = serviceWithTools(tool);

        when(llmClient.chatStream(eq(MODEL), anyList(), anyList(), any(), any()))
                .thenReturn(responseWithToolCall("call_1", "big_tool", "{}"))
                .thenReturn(responseWithContent("Done."));

        service.chatStream(new AgentRequest(PROMPT, MODEL), new AgentStreamListener() {}, "global", null);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chatStream(eq(MODEL), captor.capture(), anyList(), any(), any());
        ChatMessage toolMessage = captor.getAllValues().get(1).stream()
                .filter(m -> "tool".equals(m.role())).findFirst().orElseThrow();
        assertThat(toolMessage.content()).isEqualTo(big);
    }

    private AgentService serviceWithTools(LocalTool... tools) {
        return new AgentService(
                llmClient,
                registry(tools),
                jitRegistry(Path.of(".")),
                objectMapper,
                FIVE_MINUTES,
                DEFAULT_MODEL,
                MAX_ITERATIONS,
                defaultRegistry(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static final class RecordingLocalTool implements LocalTool {
        private final String name;
        private final String result;
        private Map<String, Object> lastArgs = Map.of();
        private int callCount;
        private Duration delay = Duration.ZERO;
        private String throwMessage;

        RecordingLocalTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public String execute(Map<String, Object> arguments) throws Exception {
            return execute(arguments, new ToolContext(null));
        }

        @Override
        public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
            callCount++;
            lastArgs = arguments;
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            if (throwMessage != null) {
                throw new RuntimeException(throwMessage);
            }
            return result;
        }
    }

    private static final class RecordingChannelTool implements LocalTool {
        private List<String> seen;

        @Override public String name() { return "echo_channel"; }
        @Override public String description() { return "Echoes the channel history it can see."; }
        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }
        @Override public String execute(Map<String, Object> arguments) {
            return execute(arguments, new ToolContext(null));
        }
        @Override public String execute(Map<String, Object> arguments, ToolContext ctx) {
            seen = ctx.channelMessages();
            return "ok";
        }
    }

    private static final class RecordingListener implements AgentStreamListener {
        private final List<String> tokens = new java.util.ArrayList<>();
        private final List<String> toolCalls = new java.util.ArrayList<>();
        private final List<String> toolResults = new java.util.ArrayList<>();

        @Override public void onContent(String delta) { tokens.add(delta); }
        @Override public void onToolCall(String toolName, String arguments) { toolCalls.add(toolName); }
        @Override public void onToolResult(String toolName, String result) { toolResults.add(result); }
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
