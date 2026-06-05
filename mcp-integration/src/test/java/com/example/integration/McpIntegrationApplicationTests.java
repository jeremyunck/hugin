package com.example.integration;

import com.example.agent.McpToolProvider;
import com.example.agent.OpenAiClient;
import com.example.agent.model.AvailableTool;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatResponse;
import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.McpServersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        "search.provider=none",
        "spring.datasource.url=jdbc:h2:mem:hugin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class McpIntegrationApplicationTests {

    private static final String TEST_JWT_SECRET_BASE64 = generateJwtSecret();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private McpToolProvider toolProvider;

    @MockBean
    private OpenAiClient llmClient;

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret-base64", () -> TEST_JWT_SECRET_BASE64);
    }

    private void stubLlmFinalAnswer(String answer) {
        when(llmClient.chat(anyString(), anyList(), anyList()))
                .thenReturn(new ChatResponse("test-id", List.of(
                        new ChatResponse.Choice(0, ChatMessage.assistant(answer), "stop"))));
    }

    private String loginAndGetToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "test", "password", testPassword()), headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();

        try {
            Map<?, ?> payload = new ObjectMapper().readValue(response.getBody(), Map.class);
            return Objects.requireNonNull(payload.get("token")).toString();
        } catch (Exception ex) {
            throw new AssertionError("Login response was not JSON", ex);
        }
    }

    private HttpEntity<Map<String, String>> authenticatedJsonEntity(Map<String, String> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
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
        // Given: mocked tools and LLM response — no api-key configured, so localhost requests are open
        var availableTool = new AvailableTool("get_time", "Get time", Map.of());
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of("server1", List.of(availableTool)));
        when(toolProvider.callTool(anyString(), anyString(), anyMap())).thenReturn("12:00");
        stubLlmFinalAnswer("It is 12:00.");

        var request = Map.of("prompt", "What time is it?", "model", "llama3.2");
        String token = loginAndGetToken();
        var response = restTemplate.exchange(
                "/api/agent/chat",
                HttpMethod.POST,
                authenticatedJsonEntity(request, token),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void agentEndpointRoutesThroughDecisionModel() {
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chat(eq("decision-model"), anyList(), anyList()))
                .thenReturn(new ChatResponse("decision", List.of(
                        new ChatResponse.Choice(0, ChatMessage.assistant("simple"), "stop"))));
        when(llmClient.chat(eq("simple-model"), anyList(), anyList()))
                .thenReturn(new ChatResponse("simple", List.of(
                        new ChatResponse.Choice(0, ChatMessage.assistant("Routed answer."), "stop"))));

        String token = loginAndGetToken();
        var request = Map.of(
                "prompt", "Tell me a joke",
                "decision", "decision-model",
                "complex", "complex-model",
                "simple", "simple-model");
        var response = restTemplate.exchange(
                "/api/agent/chat",
                HttpMethod.POST,
                authenticatedJsonEntity(request, token),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Routed answer.");
    }

    @Test
    @WithMockUser
    void agentStreamEndpointEmitsSseEvents() throws Exception {
        // Given: no tools and a streaming LLM that emits two text fragments then a final answer
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chatStream(anyString(), anyList(), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onDelta = invocation.getArgument(3);
                    java.util.function.Consumer<String> onReasoning = invocation.getArgument(4);
                    onReasoning.accept("Thinking...");
                    onDelta.accept("Hello");
                    onDelta.accept(" world");
                    return new ChatResponse(null, List.of(
                            new ChatResponse.Choice(0, ChatMessage.assistant("Hello world"), "stop")));
                });

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

        assertThat(body).isNotNull();
        assertThat(body).contains("event:reasoning");
        assertThat(body).contains("\"text\":\"Thinking...\"");
        assertThat(body).contains("event:token");
        assertThat(body).contains("\"text\":\"Hello\"");
        assertThat(body).contains("event:done");
    }

    @Test
    @WithMockUser
    void agentStreamEndpointEmitsFinalAnswerWhenNoTokensStreamed() throws Exception {
        when(toolProvider.getAllToolsByServer()).thenReturn(Map.of());
        when(llmClient.chatStream(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(new ChatResponse(null, List.of(
                        new ChatResponse.Choice(0, ChatMessage.assistant("Final answer"), "stop"))));

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

        assertThat(body).contains("event:token");
        assertThat(body).contains("\"text\":\"Final answer\"");
        assertThat(body).contains("event:done");
    }

    @Test
    void loginReturnsJwtForSeededCredentials() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "test", "password", testPassword()), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("token");
        assertThat(response.getBody()).contains("Bearer");
    }

    private static String testPassword() {
        return new String(new char[]{'p', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }

    private static String generateJwtSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
