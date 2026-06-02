package com.example.integration.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
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
class WebSearchToolTest {

    static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    static final String MODEL = "perplexity/sonar";
    static final String API_KEY = "sk-test-key";

    @Mock HttpClient httpClient;

    ObjectMapper objectMapper = new ObjectMapper();
    WebSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new WebSearchTool(API_KEY, ENDPOINT, MODEL, objectMapper, httpClient);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    void returnsContentOnSuccess() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"Search result text\"}}]}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "latest AI news")))
                .isEqualTo("Search result text");
    }

    @Test
    void appendsCitationsFromRootCitationsArray() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"Summary [1][2]\"}}],"
                + "\"citations\":[\"https://example.com/a\",\"https://example.com/b\"]}"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of("query", "test"));
        assertThat(result)
                .contains("Summary [1][2]")
                .contains("Sources:")
                .contains("[1] https://example.com/a")
                .contains("[2] https://example.com/b");
    }

    @Test
    void appendsCitationsFromMessageAnnotations() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"Summary\","
                + "\"annotations\":[{\"type\":\"url_citation\",\"url_citation\":"
                + "{\"url\":\"https://example.com/a\",\"title\":\"Example A\"}}]}}]}"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of("query", "test"));
        assertThat(result)
                .contains("Sources:")
                .contains("[1] Example A - https://example.com/a");
    }

    @Test
    void deduplicatesRepeatedCitationUrls() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"Summary\"}}],"
                + "\"citations\":[\"https://example.com/a\",\"https://example.com/a\"]}"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of("query", "test"));
        assertThat(result).containsOnlyOnce("https://example.com/a");
    }

    @Test
    void omitsSourcesSectionWhenNoCitations() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"Just a summary\"}}]}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "test"))).isEqualTo("Just a summary");
    }

    @Test
    void returnsUnavailableWhenApiKeyBlank() throws Exception {
        var noKeyTool = new WebSearchTool("", ENDPOINT, MODEL, objectMapper, httpClient);

        assertThat(noKeyTool.execute(Map.of("query", "test")))
                .contains("OPEN_ROUTER_API_KEY is not set");
    }

    @Test
    void returnsErrorMessageOnNonRetriable4xx() throws Exception {
        doReturn(mockResponse(401, "{\"error\":\"Unauthorized\"}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "test")))
                .contains("OpenRouter API error 401");
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        HttpResponse<String> rateLimited = mockResponse(429, "rate limited");
        HttpResponse<String> success = mockResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"Retry succeeded\"}}]}");
        doReturn(rateLimited).doReturn(success).when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "test"))).isEqualTo("Retry succeeded");
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void retriesOn5xxThenSucceeds() throws Exception {
        HttpResponse<String> serverError = mockResponse(503, "unavailable");
        HttpResponse<String> success = mockResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        doReturn(serverError).doReturn(success).when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "test"))).isEqualTo("ok");
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void throwsAfterExhaustingRetriesOnNetworkError() throws Exception {
        doThrow(new IOException("Connection refused"))
                .when(httpClient).send(any(), any());

        assertThatThrownBy(() -> tool.execute(Map.of("query", "test")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("failed after 3 attempts");
        verify(httpClient, times(3)).send(any(), any());
    }

    @Test
    void returnsErrorOnMissingContentField() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{}}]}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "test")))
                .contains("missing content field");
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void returnsErrorOnNullContentField() throws Exception {
        doReturn(mockResponse(200, "{\"choices\":[{\"message\":{\"content\":null}}]}"))
                .when(httpClient).send(any(), any());

        assertThat(tool.execute(Map.of("query", "test")))
                .contains("missing content field");
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void throwsOnMissingQueryArgument() {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void nameAndSchemaAreCorrect() {
        assertThat(tool.name()).isEqualTo("web_search");
        @SuppressWarnings("unchecked")
        var props = (Map<String, ?>) tool.inputSchema().get("properties");
        assertThat(props).containsKey("query");
    }
}
