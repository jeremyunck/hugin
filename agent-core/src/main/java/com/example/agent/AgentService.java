package com.example.agent;

import com.example.agent.model.*;
import com.example.agent.prompts.Prompts;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.LocalToolRegistry;
import com.example.agent.tool.ToolContext;
import com.example.agent.tool.WorkspaceRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Core agent loop.
 *
 * <ol>
 *   <li>Collects every built-in local tool plus all tools from every connected MCP server.</li>
 *   <li>Sends the user prompt to the configured LLM with those tools advertised.</li>
 *   <li>When the model requests a tool call, runs it locally or routes it to the owning MCP server.</li>
 *   <li>Feeds the tool result back and repeats until the model produces a final answer.</li>
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    static final int MAX_ITERATIONS = 10;


    private final OpenAiClient llmClient;
    private final McpToolProvider toolProvider;
    private final LocalToolRegistry localTools;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final String defaultModel;
    private final WorkspaceRegistry workspaceRegistry;
    private final Optional<MemoryService> memoryService;
    private final Optional<ConversationMemoryService> conversationMemory;
    private final Optional<SystemFactsService> systemFactsService;

    public AgentService(
            OpenAiClient llmClient,
            McpToolProvider toolProvider,
            LocalToolRegistry localTools,
            ObjectMapper objectMapper,
            @Value("${agent.request-timeout:5m}") Duration requestTimeout,
            @Value("${llm.model:}") String defaultModel,
            WorkspaceRegistry workspaceRegistry,
            Optional<MemoryService> memoryService,
            Optional<ConversationMemoryService> conversationMemory,
            Optional<SystemFactsService> systemFactsService) {
        this.llmClient = llmClient;
        this.toolProvider = toolProvider;
        this.localTools = localTools;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.defaultModel = defaultModel;
        this.workspaceRegistry = workspaceRegistry;
        this.memoryService = memoryService;
        this.conversationMemory = conversationMemory;
        this.systemFactsService = systemFactsService;
    }

    public record ToolSummary(String name, String description, String server, String transport) {}

    /** Returns a flat list of all tools currently available to the agent (local + MCP). */
    public List<ToolSummary> availableTools() {
        List<ToolSummary> result = new ArrayList<>();
        for (LocalTool tool : localTools.tools()) {
            result.add(new ToolSummary(tool.name(), tool.description(), "local", "built-in"));
        }
        toolProvider.getAllToolsByServer().forEach((serverName, tools) ->
            tools.forEach(t -> result.add(new ToolSummary(
                t.name(),
                t.description() != null ? t.description() : "",
                serverName,
                "mcp"
            )))
        );
        return result;
    }

    public AgentResponse chat(AgentRequest request) {
        return runLoop(request, NO_OP_LISTENER, false);
    }

    /**
     * Streaming variant of {@link #chat}: assistant text is delivered token-by-token via
     * {@code listener} as it arrives from the model, and tool calls are reported as the loop runs
     * them. Returns the same {@link AgentResponse} (final answer plus full conversation history)
     * once the loop completes.
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener) {
        return runLoop(request, listener, true);
    }

    private AgentResponse runLoop(AgentRequest request, AgentStreamListener listener, boolean stream) {
        Instant deadline = Instant.now().plus(requestTimeout);
        String model = resolveModel(request.model());

        // Build flattened tool list and a reverse lookup: tool name → server name.
        Map<String, String> toolServerMap = new LinkedHashMap<>();
        List<ToolDefinition> toolDefinitions = collectTools(toolServerMap);

        log.debug("Agent chat: model={}, tools available={} (local={}), stream={}",
                model, toolDefinitions.size(), localTools.tools().size(), stream);

        List<ChatMessage> messages = new ArrayList<>();
        systemFactsService.ifPresent(sfs -> {
            String summary = sfs.summary();
            if (!summary.isBlank()) {
                messages.add(ChatMessage.system(summary));
            }
        });
        if (!toolDefinitions.isEmpty()) {
            messages.add(ChatMessage.system(Prompts.TOOL_USE));
        }
        memoryService.ifPresent(memory -> {
            List<MemoryStore.ScoredMemory> recalled = memory.recall(request.prompt());
            if (!recalled.isEmpty()) {
                messages.add(ChatMessage.system(formatMemories(recalled)));
            }
        });
        // Replay the recent turns of this session (short-term memory) before the current prompt.
        conversationMemory.ifPresent(cm -> messages.addAll(cm.history(request.sessionId())));
        messages.add(ChatMessage.user(request.prompt()));

        String lastAssistantContent = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Agent request timed out after {}", requestTimeout);
                return new AgentResponse(
                        "Request timed out after " + requestTimeout.toSeconds() + "s.",
                        Collections.unmodifiableList(messages));
            }

            ChatResponse response = stream
                    ? llmClient.chatStream(model, messages, toolDefinitions, listener::onContent)
                    : llmClient.chat(model, messages, toolDefinitions);
            ChatResponse.Choice choice = response.choices().get(0);
            ChatMessage assistantMsg = choice.message();
            messages.add(assistantMsg);

            String finishReason = choice.finishReason();
            log.debug("Iteration {}: finish_reason={}, has_tool_calls={}",
                    i, finishReason, assistantMsg.toolCalls() != null);

            // Handle tool calls regardless of finishReason — some models set
            // finish_reason to "stop" or leave it null even when tool_calls are present.
            if (assistantMsg.toolCalls() != null && !assistantMsg.toolCalls().isEmpty()) {
                if (assistantMsg.content() != null && !assistantMsg.content().isEmpty()) {
                    lastAssistantContent = assistantMsg.content();
                }
                for (ToolCall toolCall : assistantMsg.toolCalls()) {
                    listener.onToolCall(toolCall.function().name(), toolCall.function().arguments());
                    String toolResult = executeToolCall(toolCall, toolServerMap, request.sessionId());
                    listener.onToolResult(toolCall.function().name(), toolResult);
                    messages.add(ChatMessage.tool(toolCall.id(), toolResult));
                }
            } else {
                String answer = assistantMsg.content();
                if (answer == null || answer.isBlank()) {
                    answer = lastAssistantContent;
                }
                final String finalAnswer = answer;
                memoryService.ifPresent(memory -> memory.remember(request.prompt(), finalAnswer));
                conversationMemory.ifPresent(cm -> cm.record(request.sessionId(), request.prompt(), finalAnswer));
                return new AgentResponse(answer, Collections.unmodifiableList(messages));
            }
        }

        log.warn("Agent reached max iterations ({}) without a final answer", MAX_ITERATIONS);
        return new AgentResponse(
                "Reached the maximum number of tool-call iterations without a final answer.",
                Collections.unmodifiableList(messages));
    }

    /**
     * Flattens every built-in local tool plus all tools from every connected MCP server into one
     * OpenAI-format list, filling {@code toolServerMap} with a tool name → server name lookup.
     * Built-in local tools are advertised first and take precedence on name collisions.
     */
    private List<ToolDefinition> collectTools(Map<String, String> toolServerMap) {
        List<ToolDefinition> toolDefinitions = new ArrayList<>();

        for (LocalTool tool : localTools.tools()) {
            toolDefinitions.add(ToolDefinition.from(tool.name(), tool.description(), tool.inputSchema()));
        }

        toolProvider.getAllToolsByServer().forEach((serverName, tools) ->
                tools.forEach(tool -> {
                    if (localTools.find(tool.name()) != null) {
                        log.warn("MCP tool '{}' on server '{}' is shadowed by a built-in local tool",
                                tool.name(), serverName);
                        return;
                    }
                    toolServerMap.put(tool.name(), serverName);
                    toolDefinitions.add(ToolDefinition.from(tool));
                })
        );
        return toolDefinitions;
    }

    private static String formatMemories(List<MemoryStore.ScoredMemory> memories) {
        StringBuilder sb = new StringBuilder(Prompts.MEMORY_HEADER);
        int n = 1;
        for (MemoryStore.ScoredMemory memory : memories) {
            sb.append("\n\n").append(n++).append(". ").append(memory.record().text());
        }
        return sb.toString();
    }

    private static final AgentStreamListener NO_OP_LISTENER = new AgentStreamListener() {};

    private String resolveModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return defaultModel;
    }

    private String executeToolCall(ToolCall toolCall, Map<String, String> toolServerMap, String sessionId) {
        String toolName = toolCall.function().name();
        Map<String, Object> args = parseArguments(toolCall.function().arguments());

        LocalTool localTool = localTools.find(toolName);
        if (localTool != null) {
            log.debug("Executing built-in tool '{}' with args: {}", toolName, args);
            ToolContext ctx = new ToolContext(workspaceRegistry.resolve(sessionId));
            try {
                return localTool.execute(args, ctx);
            } catch (Exception e) {
                String msg = "Tool call failed: " + e.getMessage();
                log.error(msg, e);
                return msg;
            }
        }

        String serverName = toolServerMap.get(toolName);
        if (serverName == null) {
            String msg = "Tool '" + toolName + "' is not available as a built-in tool "
                    + "or on any connected MCP server.";
            log.warn(msg);
            return msg;
        }

        log.debug("Calling tool '{}' on server '{}' with args: {}", toolName, serverName, args);
        try {
            return toolProvider.callTool(serverName, toolName, args);
        } catch (Exception e) {
            String msg = "Tool call failed: " + e.getMessage();
            log.error(msg, e);
            return msg;
        }
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not parse tool arguments '{}': {}", arguments, e.getMessage());
            return Map.of();
        }
    }
}
