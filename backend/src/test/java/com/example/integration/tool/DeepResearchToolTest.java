package com.example.integration.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepResearchToolTest {

    static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    static final String MODEL = "perplexity/sonar";
    static final String API_KEY = "sk-test-key";

    @Mock HttpClient httpClient;

    ObjectMapper objectMapper = new ObjectMapper();
    DeepResearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new DeepResearchTool(new OpenRouterSearchClient(API_KEY, ENDPOINT, objectMapper, httpClient), MODEL);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    private HttpResponse<String> searchResponse(String content, String... urls) {
        StringBuilder citations = new StringBuilder();
        for (int i = 0; i < urls.length; i++) {
            if (i > 0) {
                citations.append(",");
            }
            citations.append("\"").append(urls[i]).append("\"");
        }
        return mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],"
                + "\"citations\":[" + citations + "]}");
    }

    @Test
    void gathersMultipleAnglesAndCuratesSources() throws Exception {
        // One response per default focus area; the second and third reuse a URL to test de-duplication.
        doReturn(
                searchResponse("Overview finding", "https://example.com/a", "https://example.com/b"),
                searchResponse("Recent finding", "https://example.com/b"),
                searchResponse("Debate finding", "https://example.com/c"),
                searchResponse("Data finding", "https://example.com/d"),
                searchResponse("Implications finding", "https://example.com/e"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of("topic", "quantum computing"));

        assertThat(result)
                .contains("Research brief: quantum computing")
                .contains("Overview finding")
                .contains("Recent finding")
                .contains("Implications finding")
                .contains("Sources for this section:")
                .contains("Consolidated sources (5 unique)")
                // https://example.com/b is cited by sections 1 and 2 but de-duplicated into a single
                // consolidated entry that records both sections.
                .contains("(cited in sections 1, 2)");
    }

    @Test
    void usesCallerSuppliedFocusAreas() throws Exception {
        doReturn(
                searchResponse("Cost analysis", "https://example.com/cost"),
                searchResponse("Safety analysis", "https://example.com/safety"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of(
                "topic", "nuclear power",
                "focus_areas", List.of("Cost", "Safety")));

        assertThat(result)
                .contains("1. Cost")
                .contains("2. Safety")
                .contains("Cost analysis")
                .contains("Safety analysis")
                .doesNotContain("Overview, background");
    }

    @Test
    void continuesWhenOneAngleFails() throws Exception {
        doReturn(
                searchResponse("Good finding", "https://example.com/a"),
                mockResponse(401, "{\"error\":\"unauthorized\"}"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of(
                "topic", "topic",
                "focus_areas", List.of("First", "Second")));

        assertThat(result)
                .contains("Good finding")
                .contains("search failed for this angle")
                .contains("OpenRouter API error 401");
    }

    @Test
    void reportsFailureWhenEveryAngleFails() throws Exception {
        doReturn(mockResponse(401, "{\"error\":\"unauthorized\"}"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of(
                "topic", "topic",
                "focus_areas", List.of("First", "Second")));

        assertThat(result).contains("failed").contains("none of the 2 searches");
    }

    @Test
    void capsConsolidatedSourcesAtMaxSources() throws Exception {
        doReturn(
                searchResponse("F1", "https://example.com/1", "https://example.com/2"),
                searchResponse("F2", "https://example.com/3", "https://example.com/4"))
                .when(httpClient).send(any(), any());

        String result = tool.execute(Map.of(
                "topic", "topic",
                "focus_areas", List.of("A", "B"),
                "max_sources", 2));

        assertThat(result)
                .contains("Consolidated sources (2 of 4 unique)")
                .contains("and 2 more");
    }

    @Test
    void returnsUnavailableWhenApiKeyBlank() throws Exception {
        var noKeyTool = new DeepResearchTool(
                new OpenRouterSearchClient("", ENDPOINT, objectMapper, httpClient), MODEL);

        assertThat(noKeyTool.execute(Map.of("topic", "anything")))
                .contains("OPEN_ROUTER_API_KEY is not set");
    }

    @Test
    void throwsOnMissingTopicArgument() {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void nameAndSchemaAreCorrect() {
        assertThat(tool.name()).isEqualTo("deep_research");
        @SuppressWarnings("unchecked")
        var props = (Map<String, ?>) tool.inputSchema().get("properties");
        assertThat(props).containsKey("topic").containsKey("focus_areas").containsKey("max_sources");
    }
}
