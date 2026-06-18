package com.example.integration.controller;

import com.example.agent.AgentStreamListener;
import com.example.agent.CloudAgentService;
import com.example.agent.model.AgentInfo;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.AgentStatus;
import com.example.integration.service.CloudAgentEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudAgentControllerTest {

    @Mock
    CloudAgentService cloudAgentService;

    @Mock
    CloudAgentEventStore eventStore;

    CloudAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new CloudAgentController(
                cloudAgentService,
                eventStore,
                new ObjectMapper(),
                Executors.newCachedThreadPool(),
                Duration.ofMinutes(5)
        );
    }

    @Test
    void handleErrorReturnsInternalServerError() {
        var response = controller.handleError(
                new RuntimeException("Something went wrong"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Something went wrong"));
    }

    @Test
    void createStopsWorkWhenClientDisconnects() throws InterruptedException {
        var emitter = new FailingEmitter(2);
        controller = new CloudAgentController(
                cloudAgentService,
                eventStore,
                new ObjectMapper(),
                Executors.newCachedThreadPool(),
                Duration.ofMinutes(5)
        ) {
            @Override
            SseEmitter createEmitter() {
                return emitter;
            }
        };
        when(cloudAgentService.create(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentInfo("agent-1", "repo", "main", "branch", AgentStatus.RUNNING, Instant.now(), "task"));
        var latch = new CountDownLatch(1);
        var continuedAfterDisconnect = new AtomicInteger();
        doAnswer(invocation -> {
            latch.countDown();
            AgentStreamListener listener = invocation.getArgument(2);
            listener.onContent("partial");
            continuedAfterDisconnect.incrementAndGet();
            return new CloudAgentService.RunResult(new AgentResponse("done", List.of()), Optional.empty(), false);
        }).when(cloudAgentService).run(anyString(), anyString(), any(AgentStreamListener.class));

        controller.create(new CloudAgentController.CreateAgentRequest("repo", "task", "main", "model"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(continuedAfterDisconnect).hasValue(0);
    }

    @Test
    void handleErrorSuppressesBodyForSseRequests() {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept", "text/event-stream");
        var response = new MockHttpServletResponse();

        var result = controller.handleError(new RuntimeException("stream failed"), request, response);

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

        var result = controller.handleError(new RuntimeException("stream failed"), request, response);

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

        var result = controller.handleError(
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "bad input"),
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
}
