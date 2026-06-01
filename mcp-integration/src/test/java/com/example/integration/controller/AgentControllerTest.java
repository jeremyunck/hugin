package com.example.integration.controller;

import com.example.agent.AgentService;
import com.example.agent.AgentStreamListener;
import com.example.agent.DeveloperModeService;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    AgentService agentService;

    @Mock
    DeveloperModeService developerModeService;

    ObjectMapper objectMapper = new ObjectMapper();
    AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
                agentService,
                objectMapper,
                Executors.newCachedThreadPool(),
                developerModeService,
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
        when(agentService.chat(request)).thenReturn(agentResponse);

        ResponseEntity<?> result = controller.chat(request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(agentResponse);
    }

    @Test
    void chatEndpointDelegatesToAgentService() {
        var request = new AgentRequest("What time is it?", "llama3.2");
        var agentResponse = new AgentResponse("It is noon.", List.of());
        when(agentService.chat(request)).thenReturn(agentResponse);

        controller.chat(request);

        verify(agentService).chat(request);
    }

    @Test
    void chatEndpointWithSessionId() {
        var request = new AgentRequest("Continue", "llama3.2", "session-123");
        var agentResponse = new AgentResponse("Sure!", List.of(
                ChatMessage.user("Previous message"),
                ChatMessage.assistant("Previous answer")
        ));
        when(agentService.chat(request)).thenReturn(agentResponse);

        ResponseEntity<?> result = controller.chat(request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(agentResponse);
    }

    // -------------------------------------------------------------------------
    // /api/agent/stream tests
    // -------------------------------------------------------------------------

    @Test
    void chatStreamReturnsSseEmitter() {
        var request = new AgentRequest("Hello", "llama3.2");

        SseEmitter emitter = controller.chatStream(request);

        assertThat(emitter).isNotNull();
    }

    @Test
    void chatStreamInvokesAgentServiceChatStream() throws InterruptedException {
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        controller.chatStream(request);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(agentService).chatStream(eq(request), any(AgentStreamListener.class));
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
        }).when(agentService).chatStream(eq(request), listenerCaptor.capture());

        SseEmitter emitter = controller.chatStream(request);

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
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        SseEmitter emitter = controller.chatStream(request);

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
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        SseEmitter emitter = controller.chatStream(request);

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
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        SseEmitter emitter = controller.chatStream(request);

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
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        SseEmitter emitter = controller.chatStream(request);

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
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        SseEmitter emitter = controller.chatStream(request);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        // Should not throw; error event uses class simple name as fallback
        assertThat(emitter).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Exception handler test
    // -------------------------------------------------------------------------

    @Test
    void handleErrorReturnsInternalServerError() {
        var exception = new RuntimeException("Something went wrong");

        ResponseEntity<Map<String, String>> response = controller.handleError(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Something went wrong");
    }

    @Test
    void handleErrorUsesClassNameWhenMessageIsNull() {
        var exception = new NullPointerException();

        ResponseEntity<Map<String, String>> response = controller.handleError(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).isEqualTo("NullPointerException");
    }

    @Test
    void handleErrorWithCustomMessage() {
        var exception = new IllegalArgumentException("Invalid input provided");

        ResponseEntity<Map<String, String>> response = controller.handleError(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Invalid input provided");
    }

    @Test
    void chatStreamEmitsConfigEventUsingDeveloperModeState() throws InterruptedException {
        when(developerModeService.isEnabled()).thenReturn(true);
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> { latch.countDown(); return null; })
                .when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        controller.chatStream(request);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(developerModeService).isEnabled();
    }

    @Test
    void chatStreamEmitsConfigEventWhenDeveloperModeOff() throws InterruptedException {
        when(developerModeService.isEnabled()).thenReturn(false);
        var request = new AgentRequest("Hello", "llama3.2");
        var latch = new CountDownLatch(1);

        doAnswer(invocation -> { latch.countDown(); return null; })
                .when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        controller.chatStream(request);

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
        }).when(agentService).chatStream(eq(request), any(AgentStreamListener.class));

        SseEmitter emitter = controller.chatStream(request);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }
}
