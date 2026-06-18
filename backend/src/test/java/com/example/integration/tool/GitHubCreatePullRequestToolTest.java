package com.example.integration.tool;

import com.example.integration.github.GitHubAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubCreatePullRequestToolTest {

    @Mock GitHubAppService github;
    ObjectMapper objectMapper = new ObjectMapper();
    GitHubCreatePullRequestTool tool;

    @BeforeEach
    void setUp() {
        tool = new GitHubCreatePullRequestTool(github, objectMapper);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        var r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    void nameAndSchemaAreCorrect() {
        assertThat(tool.name()).isEqualTo("github_create_pull_request");
        assertThat(tool.description()).contains("Create a pull request");
        assertThat(tool.description()).contains("owner/repo");

        @SuppressWarnings("unchecked")
        var props = (Map<String, ?>) tool.inputSchema().get("properties");
        assertThat(props).containsKeys("repository", "title", "head", "base", "body");

        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.inputSchema().get("required");
        assertThat(required).containsExactly("repository", "title", "head", "base");
    }

    @Test
    void availabilityTracksGitHubConnection() {
        when(github.isActive()).thenReturn(true);
        assertThat(tool.isAvailable()).isTrue();

        when(github.isActive()).thenReturn(false);
        assertThat(tool.isAvailable()).isFalse();
    }

    @Test
    void createsPullRequestSuccessfully() throws Exception {
        HttpResponse<String> response = mockResponse(201,
                "{\"number\":42,\"html_url\":\"https://github.com/octocat/hello-world/pull/42\"}");
        when(github.api("POST", "/repos/octocat/hello-world/pulls",
                "{\"title\":\"Fix bug\",\"head\":\"feature\",\"base\":\"main\"}"))
                .thenReturn(response);

        String result = tool.execute(Map.of(
                "repository", "octocat/hello-world",
                "title", "Fix bug",
                "head", "feature",
                "base", "main"));

        assertThat(result).isEqualTo("Created pull request #42: https://github.com/octocat/hello-world/pull/42");
    }

    @Test
    void createsPullRequestWithBody() throws Exception {
        HttpResponse<String> response = mockResponse(201,
                "{\"number\":43,\"html_url\":\"https://github.com/octocat/hello-world/pull/43\"}");
        when(github.api("POST", "/repos/octocat/hello-world/pulls",
                "{\"title\":\"Add feature\",\"head\":\"feat/new-stuff\",\"base\":\"main\",\"body\":\"Closes #10\"}"))
                .thenReturn(response);

        String result = tool.execute(Map.of(
                "repository", "octocat/hello-world",
                "title", "Add feature",
                "head", "feat/new-stuff",
                "base", "main",
                "body", "Closes #10"));

        assertThat(result).isEqualTo("Created pull request #43: https://github.com/octocat/hello-world/pull/43");
    }

    @Test
    void returnsErrorOnApiFailure() throws Exception {
        HttpResponse<String> response = mockResponse(422,
                "{\"message\":\"Validation failed\"}");
        when(github.api("POST", "/repos/octocat/hello-world/pulls",
                "{\"title\":\"Fix\",\"head\":\"fix\",\"base\":\"main\"}"))
                .thenReturn(response);

        String result = tool.execute(Map.of(
                "repository", "octocat/hello-world",
                "title", "Fix",
                "head", "fix",
                "base", "main"));

        assertThat(result).contains("Failed to create pull request")
                .contains("422")
                .contains("Validation failed");
    }

    @Test
    void returnsErrorOnInvalidRepositoryFormat() throws Exception {
        String result = tool.execute(Map.of(
                "repository", "invalid",
                "title", "Fix",
                "head", "fix",
                "base", "main"));

        assertThat(result).contains("Invalid repository 'invalid'")
                .contains("owner/repo");
    }

    @Test
    void rejectsMissingRequiredArguments() {
        var result = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(Map.of()));

        assertThat(result.getMessage()).contains("repository");
    }

    @Test
    void rejectsMissingTitle() {
        var result = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(Map.of("repository", "octocat/hello-world")));

        assertThat(result.getMessage()).contains("title");
    }

    @Test
    void rejectsMissingHead() {
        var result = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(Map.of(
                        "repository", "octocat/hello-world",
                        "title", "Fix")));

        assertThat(result.getMessage()).contains("head");
    }

    @Test
    void rejectsMissingBase() {
        var result = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(Map.of(
                        "repository", "octocat/hello-world",
                        "title", "Fix",
                        "head", "fix")));

        assertThat(result.getMessage()).contains("base");
    }
}
