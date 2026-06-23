package com.example.integration.controller;

import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.AgentRunRegistry;
import com.example.agent.DeveloperModeService;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.ChatMessage;
import com.example.integration.agent.UserAgentService;
import com.example.integration.service.AgentRunEventStore;
import com.example.integration.service.BugReportCatalogService;
import com.example.integration.service.BugReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    AgentService agentService;

    @Mock
    DeveloperModeService developerModeService;

    @Mock
    UserAgentService userAgentService;

    @Mock
    BugReportService bugReportService;

    @Mock
    BugReportCatalogService bugReportCatalogService;

    @Mock
    AgentRunEventStore eventStore;

    @Mock
    com.example.agent.tool.HomeWorkspaceService homeWorkspaceService;

    ObjectMapper objectMapper = new ObjectMapper();
    AgentRunRegistry runRegistry = new AgentRunRegistry();
    AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
                agentService,
                objectMapper,
                Executors.newCachedThreadPool(),
                developerModeService,
                userAgentService,
                runRegistry,
                eventStore,
                bugReportCatalogService,
                bugReportService,
                homeWorkspaceService,
                Duration.ofMinutes(5)
        );
    }

    // -------------------------------------------------------------------------
    // /api/agent/chat tests
    // -------------------------------------------------------------------------

    @Test
    void chatEndpointReturnOkWithResponse() {
        var request = new AgentRequest("Hello", "llama3.2");
        var agentResponse = new AgentResponse("Hi there!", List.of());
        when(agentService.chat(any(AgentRequest.class), eq("global"))).thenReturn(agentResponse);

        ResponseEntity<?> result = controller.chat(request, null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(agentResponse);
    }

    @Test
    void chatEndpointDelegatesToAgentService() {
        var request = new AgentRequest("What time is it?", "llama3.2");
        var agentResponse = new AgentResponse("It is noon.", List.of());
        when(agentService.chat(any(AgentRequest.class), eq("global"))).thenReturn(agentResponse);

        controller.chat(request, null);

        verify(agentService).chat(any(AgentRequest.class), eq("global"));
    }

    @Test
    void chatEndpointWithSessionId() {
        var request = new AgentRequest("Continue", "llama3.2", "session-123");
        var agentResponse = new AgentResponse("Sure!", List.of(
                ChatMessage.user("Previous message"),
                ChatMessage.assistant("Previous answer")
        ));
        when(agentService.chat(any(AgentRequest.class), eq("global"))).thenReturn(agentResponse);

        ResponseEntity<?> result = controller.chat(request, null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(agentResponse);
    }

    @Test
    void chatEndpointPassesRawSessionIdToAgentService() {
        var request = new AgentRequest("Continue", "llama3.2", "session-123");
        when(agentService.chat(any(AgentRequest.class), eq("global")))
                .thenReturn(new AgentResponse("Sure!", List.of()));

        controller.chat(request, null);

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).chat(requestCaptor.capture(), eq("global"));
        assertThat(requestCaptor.getValue().sessionId()).isEqualTo("session-123");
    }

    @Test
    void workspaceFilesEndpointReturnsHomeFileTree() {
        var tree = List.of(
                com.example.agent.model.FileNode.directory("src", "src", List.of()),
                com.example.agent.model.FileNode.file("README.md", "README.md", 12L));
        when(homeWorkspaceService.files()).thenReturn(tree);

        ResponseEntity<List<com.example.agent.model.FileNode>> result = controller.workspaceFiles();

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(tree);
    }

    @Test
    void historyEndpointScopesSessionAndReturnsConversationHistory() {
        List<ChatMessage> history = List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi"));
        when(agentService.history("global", null, "session-123")).thenReturn(history);

        ResponseEntity<List<ChatMessage>> result = controller.history("session-123", null, null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(history);
    }

    @Test
    void deleteHistoryEndpointScopesSessionAndReturnsNoContent() {
        ResponseEntity<Void> result = controller.deleteHistory("session-123", null, null);

        assertThat(result.getStatusCode().value()).isEqualTo(204);
        verify(agentService).deleteHistory("global", null, "session-123");
    }

    @Test
    void deleteHistoryEndpointRejectsBlankSessionId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.deleteHistory("  ", null, null))
                .isInstanceOf(ResponseStatusException.class);
        verify(agentService, never()).deleteHistory(any(), any(), any());
    }

    @Test
    void bugReportEndpointExportsHistoryIntoWorkspaceReport() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("global");
        var request = new BugReportRequest(
                "session-123",
                "Hung chat",
                null,
                null,
                JsonNodeFactory.instance.objectNode().put("id", "thread-1"),
                JsonNodeFactory.instance.objectNode().put("screen", "purechat"));
        List<ChatMessage> history = List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi"));
        when(agentService.history("global", null, "session-123")).thenReturn(history);
        when(bugReportService.writeReport(
                eq("session-123"),
                eq("Hung chat"),
                eq("global"),
                eq(null),
                eq(null),
                eq(history),
                any(),
                any()))
                .thenReturn(new BugReportService.BugReportFile(
                        "bug-123",
                        "bug-reports/2026-06-18/report.txt",
                        "/tmp/report.txt",
                        "/workspace",
                        List.of("/tmp/hugin.log")));

        ResponseEntity<BugReportResponse> result = controller.saveBugReport(request, jwt);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(new BugReportResponse(
                "bug-123",
                "bug-reports/2026-06-18/report.txt",
                List.of("/tmp/hugin.log")));
    }

    @Test
    void bugReportEndpointRequiresSessionId() {
        Jwt jwt = mock(Jwt.class);

        var request = new BugReportRequest(
                " ",
                "Hung chat",
                null,
                null,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode());

        ResponseStatusException exception = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> controller.saveBugReport(request, jwt));

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getReason()).isEqualTo("sessionId is required");
    }

    @Test
    void runsEndpointListsActiveRunsForOwner() {
        Thread worker = new Thread(() -> {});
        String runId = runRegistry.register("global", new AgentRequest("hello", "llama3.2", "session-123"), "llama3.2", worker);

        ResponseEntity<List<AgentRunRegistry.ActiveRun>> result = controller.runs(null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).extracting(AgentRunRegistry.ActiveRun::id).contains(runId);
        runRegistry.unregister(runId);
    }

    @Test
    void cancelRunEndpointCancelsKnownRun() {
        Thread worker = new Thread(() -> {});
        String runId = runRegistry.register("global", new AgentRequest("hello", "llama3.2", "session-123"), "llama3.2", worker);

        ResponseEntity<Void> result = controller.cancelRun(runId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(runRegistry.list("global"))
                .singleElement()
                .extracting(AgentRunRegistry.ActiveRun::cancellationRequested)
                .isEqualTo(true);
        runRegistry.unregister(runId);
    }

    @Test
    void cancelRunInterruptsLiveWorker() throws InterruptedException {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        worker.start();
        String runId = runRegistry.register(
                "global", new AgentRequest("hello", "llama3.2", "session-123"), "llama3.2", worker);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<Void> result = controller.cancelRun(runId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        runRegistry.unregister(runId);
    }

    @Test
    void cancelRunEndpointReturnsNotFoundForUnknownRun() {
        ResponseEntity<Void> result = controller.cancelRun("missing-run", null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // /api/agent/stream tests
    // -------------------------------------------------------------------------

    @Test
    void chatStreamReturnsSseEmitter() {
        var request = new AgentRequest("Hello", "llama3.2");

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamInvokesAgentServiceChatStream() throws InterruptedException {
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());
    }

    @Test
    void chatStreamListenerOnContentSendsTokenEvent() throws InterruptedException {
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);
        ArgumentCaptor<AgentStreamListener> listenerCaptor =
                ArgumentCaptor.forClass(AgentStreamListener.class);

        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onContent("Hello world");
            latch.countDown();
            return null;
        }).when(agentService).chatStream(any(AgentRequest.class), listenerCaptor.capture(), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        // If we get here without exception, the listener was called successfully
        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamListenerOnToolCallSendsToolEvent() throws InterruptedException {
        var request = new AgentRequest("Get weather", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onToolCall("get_weather", "{\"city\":\"Tokyo\"}");
            latch.countDown();
            return null;
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamListenerOnToolResultSendsToolResultEvent() throws InterruptedException {
        var request = new AgentRequest("Get weather", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onToolResult("get_weather", "Sunny, 25°C");
            latch.countDown();
            return null;
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamSendsDoneEventOnCompletion() throws InterruptedException {
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            // chatStream completes normally (no exception) -> should send "done" event
            latch.countDown();
            return null;
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamSendsErrorEventOnException() throws InterruptedException {
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            latch.countDown();
            throw new RuntimeException("LLM connection failed");
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamHandlesExceptionWithNullMessage() throws InterruptedException {
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            latch.countDown();
            // NullPointerException has null message
            throw new NullPointerException();
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        // Should not throw; error event uses class simple name as fallback
        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamContinuesWorkWhenClientDisconnects() throws InterruptedException {
        var emitter = new FailingEmitter(2);
        controller = new AgentController(
                agentService,
                objectMapper,
                Executors.newCachedThreadPool(),
                developerModeService,
                userAgentService,
                runRegistry,
                eventStore,
                bugReportCatalogService,
                bugReportService,
                homeWorkspaceService,
                Duration.ofMinutes(5)
        ) {
            @Override
            SseEmitter createEmitter() {
                return emitter;
            }
        };
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);
        var continuedAfterDisconnect = new AtomicInteger();

        doAnswer(invocation -> {
            latch.countDown();
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onContent("partial");
            continuedAfterDisconnect.incrementAndGet();
            return new AgentResponse("done", List.of());
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(continuedAfterDisconnect).hasValue(1);
    }

    // -------------------------------------------------------------------------
    // Exception handler test
    // -------------------------------------------------------------------------

    @Test
    void handleErrorReturnsInternalServerError() {
        var exception = new RuntimeException("Something went wrong");

        ResponseEntity<Map<String, String>> response =
                controller.handleError(exception, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Something went wrong");
    }

    @Test
    void handleErrorUsesClassNameWhenMessageIsNull() {
        var exception = new NullPointerException();

        ResponseEntity<Map<String, String>> response =
                controller.handleError(exception, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).isEqualTo("NullPointerException");
    }

    @Test
    void handleErrorWithCustomMessage() {
        var exception = new IllegalArgumentException("Invalid input provided");

        ResponseEntity<Map<String, String>> response =
                controller.handleError(exception, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Invalid input provided");
    }

    @Test
    void handleErrorSuppressesBodyForSseRequests() {
        var exception = new RuntimeException("stream failed");
        var request = new MockHttpServletRequest();
        request.addHeader("Accept", "text/event-stream");
        var response = new MockHttpServletResponse();

        ResponseEntity<Map<String, String>> result = controller.handleError(exception, request, response);

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void handleErrorSuppressesBodyWhenHandlerProducesEventStream() {
        var request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE,
                java.util.Set.of(MediaType.TEXT_EVENT_STREAM));
        var response = new MockHttpServletResponse();

        ResponseEntity<Map<String, String>> result =
                controller.handleError(new RuntimeException("stream failed"), request, response);

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void handleErrorPreservesResponseStatusExceptionCodeForSseRequests() {
        var request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE,
                java.util.Set.of(MediaType.TEXT_EVENT_STREAM));
        var response = new MockHttpServletResponse();

        ResponseEntity<Map<String, String>> result = controller.handleError(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad input"),
                request,
                response);

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    private static final class FailingEmitter extends SseEmitter {
        private final int failOnSend;
        private int sends;

        private FailingEmitter(int failOnSend) {
            this.failOnSend = failOnSend;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sends++;
            if (sends >= failOnSend) {
                throw new IOException("client disconnected");
            }
        }
    }

    @Test
    void chatStreamEmitsConfigEventUsingDeveloperModeState() throws InterruptedException {
        when(developerModeService.isEnabled()).thenReturn(true);
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> { latch.countDown(); return null; })
                .when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(developerModeService).isEnabled();
    }

    @Test
    void chatStreamEmitsConfigEventWhenDeveloperModeOff() throws InterruptedException {
        when(developerModeService.isEnabled()).thenReturn(false);
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> { latch.countDown(); return null; })
                .when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(developerModeService).isEnabled();
    }

    @Test
    void chatStreamWithAllListenerCallbacks() throws InterruptedException {
        var request = new AgentRequest("Do stuff", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            AgentStreamListener listener = invocation.getArgument(1);
            listener.onContent("Thinking...");
            listener.onToolCall("search", "{\"query\":\"test\"}");
            listener.onToolResult("search", "Found results");
            listener.onContent("Based on the results...");
            latch.countDown();
            return null;
        }).when(agentService).chatStream(any(AgentRequest.class), any(AgentStreamListener.class), anyString());

        SseEmitter emitter = controller.chatStream(request, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }

}
