package com.example.integration.tool;

import com.example.agent.model.ChatAttachment;
import com.example.agent.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DescribeImageToolTest {

    static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    static final String MODEL = "openai/gpt-4o-mini";
    static final String API_KEY = "sk-test-key";

    static final ChatAttachment PNG = new ChatAttachment(
            "bird.png", "image/png", "data:image/png;base64,abc123", 123L);
    static final ChatAttachment JPEG = new ChatAttachment(
            "cat.jpeg", "image/jpeg", "data:image/jpeg;base64,def456", 456L);

    @Mock HttpClient httpClient;

    ObjectMapper objectMapper = new ObjectMapper();
    DescribeImageTool tool;

    @BeforeEach
    void setUp() {
        tool = new DescribeImageTool(API_KEY, ENDPOINT, MODEL, 1024, objectMapper, httpClient);
    }

    private ToolContext contextWith(ChatAttachment... attachments) {
        return new ToolContext(null, "session-1", "alice", null,
                null, null, List.of(attachments));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    void returnsDescriptionOnSuccess() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"A raven in flight.\"}}]}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of(), contextWith(PNG)))
                .isEqualTo("A raven in flight.");
    }

    @Test
    void sendsImageAndQuestionInRequestBody() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
                .when(httpClient).send(any(), any());

        tool.execute(Map.of("question", "What bird is this?"), contextWith(PNG));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        String body = bodyOf(captor.getValue());
        JsonNode root = objectMapper.readTree(body);
        JsonNode userContent = root.path("messages").path(1).path("content");
        assertThat(userContent.get(0).path("type").asText()).isEqualTo("text");
        assertThat(userContent.get(0).path("text").asText()).isEqualTo("What bird is this?");
        assertThat(userContent.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(userContent.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,abc123");
        assertThat(root.path("model").asText()).isEqualTo(MODEL);
    }

    @Test
    void describesAllImagesWhenNoIndexGiven() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
                .when(httpClient).send(any(), any());

        tool.execute(Map.of(), contextWith(PNG, JPEG));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        JsonNode userContent = objectMapper.readTree(bodyOf(captor.getValue()))
                .path("messages").path(1).path("content");
        // text part + two image parts
        assertThat(userContent).hasSize(3);
    }

    @Test
    void selectsSingleImageByIndex() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
                .when(httpClient).send(any(), any());

        tool.execute(Map.of("image_index", 1), contextWith(PNG, JPEG));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        JsonNode userContent = objectMapper.readTree(bodyOf(captor.getValue()))
                .path("messages").path(1).path("content");
        assertThat(userContent).hasSize(2); // text + the one selected image
        assertThat(userContent.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/jpeg;base64,def456");
    }

    @Test
    void returnsOutOfRangeMessageForBadIndex() throws Exception {
        assertThat(tool.execute(Map.of("image_index", 5), contextWith(PNG)))
                .contains("out of range");
        verify(httpClient, times(0)).send(any(), any());
    }

    @Test
    void returnsMessageWhenNoImageAttached() throws Exception {
        assertThat(tool.execute(Map.of(), contextWith()))
                .contains("No image is attached");
        verify(httpClient, times(0)).send(any(), any());
    }

    @Test
    void ignoresNonImageAttachments() throws Exception {
        ChatAttachment pdf = new ChatAttachment("doc.pdf", "application/pdf",
                "data:application/pdf;base64,zzz", 10L);
        assertThat(tool.execute(Map.of(), contextWith(pdf)))
                .contains("No image is attached");
        verify(httpClient, times(0)).send(any(), any());
    }

    @Test
    void returnsUnavailableWhenApiKeyBlank() throws Exception {
        var noKeyTool = new DescribeImageTool("", ENDPOINT, MODEL, 1024, objectMapper, httpClient);
        assertThat(noKeyTool.isAvailable()).isFalse();
        assertThat(noKeyTool.execute(Map.of(), contextWith(PNG)))
                .contains("OPEN_ROUTER_API_KEY is not set");
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        HttpResponse<String> rateLimited = mockResponse(429, "rate limited");
        HttpResponse<String> success = mockResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"Retry succeeded\"}}]}");
        doReturn(rateLimited).doReturn(success).when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of(), contextWith(PNG))).isEqualTo("Retry succeeded");
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void returnsErrorMessageOnNonRetriable4xx() throws Exception {
        doReturn(mockResponse(401, "{\"error\":\"Unauthorized\"}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of(), contextWith(PNG)))
                .contains("OpenRouter API error 401");
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void throwsAfterExhaustingRetriesOnNetworkError() throws Exception {
        doThrow(new IOException("Connection refused"))
                .when(httpClient).send(any(), any());

        assertThatThrownBy(() -> tool.execute(Map.of(), contextWith(PNG)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("failed after 3 attempts");
        verify(httpClient, times(3)).send(any(), any());
    }

    @Test
    void returnsErrorOnMissingContentField() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{}}]}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of(), contextWith(PNG)))
                .contains("missing content");
    }

    @Test
    void nameAndSchemaAreCorrect() {
        assertThat(tool.name()).isEqualTo("describe_image");
        @SuppressWarnings("unchecked")
        var props = (Map<String, ?>) tool.inputSchema().get("properties");
        assertThat(props).containsKeys("question", "image_index");
    }

    private static String bodyOf(HttpRequest request) {
        return request.bodyPublisher()
                .map(BodyCapture::capture)
                .orElse("");
    }

    /** Minimal subscriber that materialises an HttpRequest's string body for assertions. */
    private static final class BodyCapture {
        static String capture(java.net.http.HttpRequest.BodyPublisher publisher) {
            var sb = new StringBuilder();
            var latch = new java.util.concurrent.CountDownLatch(1);
            publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(java.nio.ByteBuffer item) {
                    sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
                }

                @Override
                public void onError(Throwable throwable) {
                    latch.countDown();
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }
            });
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return sb.toString();
        }
    }
}
