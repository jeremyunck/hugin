package com.example.agent;

import com.example.agent.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core agent loop.
 *
 * <ol>
 *   <li>Fetches all tools from every connected MCP server.</li>
 *   <li>Sends the user prompt to Ollama with those tools advertised.</li>
 *   <li>When the model requests a tool call, routes it to the correct MCP server.</li>
 *   <li>Feeds the tool result back and repeats until the model produces a final answer.</li>
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    static final int MAX_ITERATIONS = 10;

    private final OllamaClient ollamaClient;
    private final McpToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public AgentService(
            OllamaClient ollamaClient,
            McpToolProvider toolProvider,
            ObjectMapper objectMapper,
            @Value("${agent.request-timeout:5m}") Duration requestTimeout) {
        this.ollamaClient = ollamaClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
    }

    public AgentResponse chat(AgentRequest request) {
        Instant deadline = Instant.now().plus(requestTimeout);

        // Build flattened tool list and a reverse lookup: tool name → server name
        Map<String, String> toolServerMap = new LinkedHashMap<>();
        List<ToolDefinition> toolDefinitions = new ArrayList<>();

        toolProvider.getAllToolsByServer().forEach((serverName, tools) ->
                tools.forEach(tool -> {
                    toolServerMap.put(tool.name(), serverName);
                    toolDefinitions.add(ToolDefinition.from(tool));
                })
        );

        log.debug("Agent chat start: model={}, prompt=\"{}\"", request.model(), request.prompt());
        log.debug("Tools available ({}): {}", toolDefinitions.size(),
                toolDefinitions.stream().map(td -> td.function().name()).collect(Collectors.joining(", ")));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user(request.prompt()));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Agent request timed out after {}", requestTimeout);
                return new AgentResponse(
                        "Request timed out after " + requestTimeout.toSeconds() + "s.",
                        Collections.unmodifiableList(messages));
            }

            ChatResponse response = ollamaClient.chat(request.model(), messages, toolDefinitions);
            ChatResponse.Choice choice = response.choices().get(0);
            ChatMessage assistantMsg = choice.message();
            messages.add(assistantMsg);

            String finishReason = choice.finishReason();
            log.debug("Iteration {}: finish_reason={}, has_tool_calls={}",
                    i, finishReason, assistantMsg.toolCalls() != null);

            // Handle tool calls regardless of finishReason — some models set
            // finish_reason to "stop" or leave it null even when tool_calls are present.
            if (assistantMsg.toolCalls() != null && !assistantMsg.toolCalls().isEmpty()) {
                log.debug("Dispatching {} tool call(s): {}", assistantMsg.toolCalls().size(),
                        assistantMsg.toolCalls().stream()
                                .map(tc -> tc.function().name())
                                .collect(Collectors.joining(", ")));
                for (ToolCall toolCall : assistantMsg.toolCalls()) {
                    String toolResult = executeToolCall(toolCall, toolServerMap);
                    log.debug("Tool '{}' result: {}", toolCall.function().name(), toolResult);
                    messages.add(ChatMessage.tool(toolCall.id(), toolResult));
                }
            } else {
                log.debug("Final answer (iteration {}): \"{}\"", i, assistantMsg.content());
                return new AgentResponse(assistantMsg.content(), Collections.unmodifiableList(messages));
            }
        }

        log.warn("Agent reached max iterations ({}) without a final answer", MAX_ITERATIONS);
        return new AgentResponse(
                "Reached the maximum number of tool-call iterations without a final answer.",
                Collections.unmodifiableList(messages));
    }

    private String executeToolCall(ToolCall toolCall, Map<String, String> toolServerMap) {
        String toolName = toolCall.function().name();
        String serverName = toolServerMap.get(toolName);

        if (serverName == null) {
            String msg = "Tool '" + toolName + "' is not available on any connected MCP server.";
            log.warn(msg);
            return msg;
        }

        Map<String, Object> args = parseArguments(toolCall.function().arguments());
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
