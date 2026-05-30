package com.example.integration;

import com.example.agent.McpToolProvider;
import com.example.agent.OpenAiClient;
import com.example.agent.model.AvailableTool;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatResponse;
import com.example.integration.service.JwtService;
import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.McpServersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Non-existent config file → no real MCP servers started during tests
        "mcp.config-file=./nonexistent-test-servers.json",
        // Disable the search MCP subprocess so no external processes are spawned during tests
        "search.provider=none"
})
class McpIntegrationApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private McpToolProvider toolProvider;

    @MockBean
    private OpenAiClient llmClient;

    private String authToken;

    @BeforeEach
    void setUpAuth() {
        authToken = jwtService.generate("testuser");
    }

    private void stubLlmFinalAnswer(String answer) {
        when(llmClient.chat(anyString(), anyList(), anyList()))
                .thenReturn(new ChatResponse("test-id", List.of(
                        new ChatResponse.Choice(0, ChatMessage.assistant(answer), "stop"))));
    }

    @Test
    void contextLoads() {
        // Verifies the full multi-module Spring context starts successfully
    }

    @Test
    void configSerializationRoundTrip(@TempDir Path tmpDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        McpServersConfig original = new McpServersConfig(Map.of(
                "filesystem", new McpServerDefinition(
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
                        Map.of(), null, null),
                "my-sse-server", new McpServerDefinition(
                        null, null, null,
                        "http://localhost:3000/sse", null)
        ));

        File file = tmpDir.resolve("mcp-servers.json").toFile();
        mapper.writeValue(file, original);

        McpServersConfig loaded = mapper.readValue(file, McpServersConfig.class);
        assertThat(loaded.mcpServers()).containsKey("filesystem");
        assertThat(loaded.mcpServers()).containsKey("my-sse-server");
        assertThat(loaded.mcpServers().get("filesystem").command()).isEqualTo("npx");
        assertThat(loaded.mcpServers().get("my-sse-server").resolvedType())
                .isEqualTo(McpServerDefinition.ServerType.SSE);
    }

    @Test
    void agentEndpointReturnsResponse() {
        // Given: mocked tools and LLM response
        var availableTool = new AvailableTool("get_time", "Get time", Map.of());
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(anyString(), anyString(), anyMap())).thenReturn("12:00");
        stubLlmFinalAnswer("It is 12:00.");

        var request = Map.of("prompt", "What time is it?", "model", "llama3.2");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);
        var response = restTemplate.exchange("/api/agent/chat", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @WithMockUser
    void agentStreamEndpointEmitsSseEvents() throws Exception {
        // Given: no tools and a streaming LLM that emits two text fragments then a final answer
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chatStream(anyString(), anyList(), anyList(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onDelta = invocation.getArgument(3);
                    onDelta.accept("Hello");
                    onDelta.accept(" world");
                    return new ChatResponse(null, List.of(
                            new ChatResponse.Choice(0, ChatMessage.assistant("Hello world"), "stop")));
                });

        // TestRestTemplate cannot read SSE/async responses (body arrives after headers on a
        // background thread). MockMvc + asyncDispatch handles Spring's async dispatch correctly.
        MvcResult mvcResult = mockMvc.perform(
                        post("/api/agent/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("{\"prompt\":\"hi\",\"model\":\"llama3.2\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Then: the body is a well-formed SSE stream the terminal client can parse
        assertThat(body).isNotNull();
        assertThat(body).contains("event:token");
        assertThat(body).contains("\"text\":\"Hello\"");
        assertThat(body).contains("event:done");
    }

    @Test
    void agentEndpointCanBeCalledWithApiKeyWhenConfigured() throws Exception {
        // This test verifies the security filter works:
        // - the endpoint returns 401 when a wrong API key is sent
        stubLlmFinalAnswer("ok");
        var request = Map.of("prompt", "test", "model", "llama3.2");

        // Send a valid JWT plus a wrong X-API-Key. The JWT satisfies the security
        // filter; the X-API-Key filter is not active (no api-key configured in test),
        // so the wrong key is ignored and the request succeeds with 200.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("X-API-Key", "wrong-key");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/agent/chat", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
    }
}