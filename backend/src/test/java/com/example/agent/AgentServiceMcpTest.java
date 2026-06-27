package com.example.agent;

import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ChatResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.tool.JustInTimeToolRegistry;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.LocalToolRegistry;
import com.example.agent.tool.OwnerScopedToolProvider;
import com.example.agent.tool.ToolContext;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies that an owner-scoped MCP tool, wired via {@link OwnerScopedToolProvider}, is executed by the
 * agent loop exactly like a built-in tool — confirming the integration point in
 * {@code AgentService.executeToolCall}.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceMcpTest {

    private static final String MODEL = "test-model";

    @Mock
    private OpenAiClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesOwnerScopedMcpToolForAuthenticatedUser() {
        AtomicReference<String> seenOwner = new AtomicReference<>();
        // A fake provider that exposes one MCP tool for owner "alice" only.
        OwnerScopedToolProvider provider = new OwnerScopedToolProvider() {
            @Override
            public List<LocalTool> tools(String owner) {
                return "alice".equals(owner) ? List.of(new FakeMcpTool(seenOwner)) : List.of();
            }

            @Override
            public LocalTool find(String owner, String name) {
                return "alice".equals(owner) && "mcp_linear_create_issue".equals(name)
                        ? new FakeMcpTool(seenOwner) : null;
            }
        };

        AgentService service = newService();
        service.setOwnerScopedToolProvider(provider);

        when(llmClient.chat(eq(MODEL), anyList(), anyList()))
                .thenReturn(responseWithToolCall("c1", "mcp_linear_create_issue", "{\"title\":\"Bug\"}"))
                .thenReturn(responseWithContent("Created."));

        AgentResponse result = service.chat(new AgentRequest(PROMPT_REQUEST(), MODEL), "alice");

        assertThat(result.response()).contains("Created.");
        assertThat(seenOwner.get()).isEqualTo("alice");
    }

    private static String PROMPT_REQUEST() {
        return "Create an issue";
    }

    private AgentService newService() {
        var props = new LocalToolProperties(true, ".", Duration.ofSeconds(30), 30_000, List.of());
        return new AgentService(
                llmClient,
                new LocalToolRegistry(List.of(), props),
                new JustInTimeToolRegistry(props, objectMapper),
                objectMapper,
                Duration.ofMinutes(5),
                "default-model",
                10,
                new WorkspaceRegistry(new Workspace(props)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Minimal MCP-style tool that records the owner it ran for, read from the ToolContext. */
    private static final class FakeMcpTool implements LocalTool {
        private final AtomicReference<String> seenOwner;

        private FakeMcpTool(AtomicReference<String> seenOwner) {
            this.seenOwner = seenOwner;
        }

        @Override
        public String name() {
            return "mcp_linear_create_issue";
        }

        @Override
        public String description() {
            return "Create a Linear issue";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public String execute(Map<String, Object> arguments) {
            return "ok";
        }

        @Override
        public String execute(Map<String, Object> arguments, ToolContext ctx) {
            seenOwner.set(ctx.username());
            return "Issue created";
        }
    }

    private static ChatResponse responseWithContent(String content) {
        return new ChatResponse("id", List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant(content), "stop")));
    }

    private static ChatResponse responseWithToolCall(String id, String name, String args) {
        var toolCall = new ToolCall(id, "function", new ToolCall.FunctionCall(name, args));
        return new ChatResponse("id", List.of(
                new ChatResponse.Choice(0,
                        ChatMessage.assistantWithToolCalls(List.of(toolCall), null),
                        "tool_calls")));
    }
}
