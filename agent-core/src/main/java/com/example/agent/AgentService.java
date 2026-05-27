package com.example.agent;

import com.example.agent.model.*;
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

    /** Prepended when any tools are advertised, to nudge models toward using them. */
    static final String TOOL_SYSTEM_PROMPT = """
            You are a helpful assistant with access to external tools. When a tool can help \
            fulfil the user's request — for example reading, writing, or editing files, \
            searching the codebase, or running shell commands — call the relevant tool instead \
            of guessing or answering from memory. You may call tools several times in sequence, \
            using each result to decide the next step. When you have gathered enough information, \
            reply to the user directly. If no tool is relevant, simply answer normally.""";

    /** Prepended to recalled memories injected into the conversation. */
    static final String MEMORY_SYSTEM_PROMPT_HEADER = """
            Relevant context recalled from long-term memory of past conversations. \
            Use it if it helps answer the user; ignore it if it is not relevant:""";

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
        String apiKey = request.apiKey();

        // Build flattened tool list and a reverse lookup: tool name → server name.
        Map<String, String> toolServerMap = new LinkedHashMap<>();
        List<ToolDefinition> toolDefinitions = collectTools(toolServerMap);

        log.debug("Agent chat: model={}, tools available={} (local={}), stream={}, byok={}",
                model, toolDefinitions.size(), localTools.tools().size(), stream,
                apiKey != null && !apiKey.isBlank() ? "yes" : "no");

        List<ChatMessage> messages = new ArrayList<>();
        systemFactsService.ifPresent(sfs -> {
            String summary = sfs.summary();
            if (!summary.isBlank()) {
                messages.add(ChatMessage.system(summary));
            }
        });
        if (!toolDefinitions.isEmpty()) {
            messages.add(ChatMessage.system(TOOL_SYSTEM_PROMPT));
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

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Agent request timed out after {}", requestTimeout);
                return new AgentResponse(
                        "Request timed out after " + requestTimeout.toSeconds() + "s.",
                        Collections.unmodifiableList(messages));
            }

            ChatResponse response = stream
                    ? llmClient.chatStream(model, messages, toolDefinitions, listener::onContent, apiKey)
                    : llmClient.chat(model, messages, toolDefinitions, apiKey);
            ChatResponse.Choice choice = response.choices().get(0);
            ChatMessage assistantMsg = choice.message();
            messages.add(assistantMsg);

            String finishReason = choice.finishReason();
            log.debug("Iteration {}: finish_reason={}, has_tool_calls={}",
                    i, finishReason, assistantMsg.toolCalls() != null);

            // Handle tool calls regardless of finishReason — some models set
            // finish_reason to "stop" or leave it null even when tool_calls are present.
            if (assistantMsg.toolCalls() != null && !assistantMsg.toolCalls().isEmpty()) {
                for (ToolCall toolCall : assistantMsg.toolCalls()) {
                    listener.onToolCall(toolCall.function().name(), toolCall.function().arguments());
                    String toolResult = executeToolCall(toolCall, toolServerMap, request.sessionId());
                    listener.onToolResult(toolCall.function().name(), toolResult);
                    messages.add(ChatMessage.tool(toolCall.id(), toolResult));
                }
            } else {
                String answer = assistantMsg.content();
                memoryService.ifPresent(memory -> memory.remember(request.prompt(), answer));
                conversationMemory.ifPresent(cm -> cm.record(request.sessionId(), request.prompt(), answer));
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
        StringBuilder sb = new StringBuilder(MEMORY_SYSTEM_PROMPT_HEADER);
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
