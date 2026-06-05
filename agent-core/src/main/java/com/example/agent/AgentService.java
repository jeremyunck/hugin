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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern ROUTING_PATTERN = Pattern.compile("\\b(simple|complex)\\b");
    private final int maxIterations;

    private final OpenAiClient llmClient;
    private final McpToolProvider toolProvider;
    private final LocalToolRegistry localTools;
    /** Built-in local tool definitions, precomputed once — the local registry is fixed after startup. */
    private final List<ToolDefinition> localToolDefinitions;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final String defaultModel;
    private final WorkspaceRegistry workspaceRegistry;
    private final Optional<MemoryService> memoryService;
    private final Optional<ConversationMemoryService> conversationMemory;
    private final Optional<SystemFactsService> systemFactsService;
    private final Optional<StartupAnnouncementService> startupAnnouncement;

    public AgentService(
            OpenAiClient llmClient,
            McpToolProvider toolProvider,
            LocalToolRegistry localTools,
            ObjectMapper objectMapper,
            @Value("${agent.request-timeout:5m}") Duration requestTimeout,
            @Value("${llm.model:}") String defaultModel,
            @Value("${agent.max-iterations:50}") int maxIterations,
            WorkspaceRegistry workspaceRegistry,
            Optional<MemoryService> memoryService,
            Optional<ConversationMemoryService> conversationMemory,
            Optional<SystemFactsService> systemFactsService,
            Optional<StartupAnnouncementService> startupAnnouncement) {
        this.llmClient = llmClient;
        this.toolProvider = toolProvider;
        this.localTools = localTools;
        List<ToolDefinition> localDefs = new ArrayList<>();
        for (LocalTool tool : localTools.tools()) {
            localDefs.add(ToolDefinition.from(tool.name(), tool.description(), tool.inputSchema()));
        }
        this.localToolDefinitions = List.copyOf(localDefs);
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.defaultModel = defaultModel;
        this.maxIterations = maxIterations;
        this.workspaceRegistry = workspaceRegistry;
        this.memoryService = memoryService;
        this.conversationMemory = conversationMemory;
        this.systemFactsService = systemFactsService;
        this.startupAnnouncement = startupAnnouncement;
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
        return runLoop(request, NO_OP_LISTENER, false, "global");
    }

    /**
     * Streaming variant of {@link #chat}: assistant text is delivered token-by-token via
     * {@code listener} as it arrives from the model, and tool calls are reported as the loop runs
     * them. Returns the same {@link AgentResponse} (final answer plus full conversation history)
     * once the loop completes.
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener) {
        return runLoop(request, listener, true, "global");
    }

    public AgentResponse chat(AgentRequest request, String owner) {
        return runLoop(request, NO_OP_LISTENER, false, normalizeOwner(owner));
    }

    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener, String owner) {
        return runLoop(request, listener, true, normalizeOwner(owner));
    }

    private AgentResponse runLoop(AgentRequest request, AgentStreamListener listener, boolean stream, String owner) {
        Instant deadline = Instant.now().plus(requestTimeout);
        RoutingSelection routing = resolveModel(request);
        String model = routing.model();

        // Build flattened tool list and a reverse lookup: tool name → server name.
        Map<String, String> toolServerMap = new LinkedHashMap<>();
        List<ToolDefinition> toolDefinitions = collectTools(toolServerMap);

        log.debug("Agent chat: model={}, route={}, decisionModel={}, tools available={} (local={}), stream={}",
                model, routing.route(), routing.decisionModel(), toolDefinitions.size(),
                localTools.tools().size(), stream);

        List<ChatMessage> messages = new ArrayList<>();
        systemFactsService.ifPresent(sfs -> {
            String summary = sfs.summary();
            if (!summary.isBlank()) {
                messages.add(ChatMessage.system(summary));
            }
        });
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(ChatMessage.system(request.systemPrompt()));
        }
        if (!toolDefinitions.isEmpty()) {
            messages.add(ChatMessage.system(Prompts.TOOL_USE));
        }
        startupAnnouncement.flatMap(StartupAnnouncementService::consume).ifPresent(notice ->
                messages.add(ChatMessage.system(
                        "You have just restarted after a self-update. " + notice
                        + " Begin your response by sharing this update notice with the user.")));
        memoryService.ifPresent(memory -> {
            List<MemoryStore.ScoredMemory> recalled = memory.recall(owner, request.prompt());
            if (!recalled.isEmpty()) {
                messages.add(ChatMessage.system(formatMemories(recalled)));
            }
        });
        // When the caller supplies its own recent-message context (e.g. Discord), it manages its own
        // short-term memory — so we neither replay nor record server-side conversation memory for it.
        boolean clientManagedContext = request.recentMessages() != null;
        if (!clientManagedContext) {
            // Replay the recent turns of this session (short-term memory) before the current prompt.
            conversationMemory.ifPresent(cm -> messages.addAll(cm.history(
                    sessionScope(owner, request.agentId(), request.sessionId()))));
        }
        messages.add(ChatMessage.user(request.prompt()));

        String lastAssistantContent = null;
        boolean hadToolCalls = false;
        boolean nudgedForEmptyResponse = false;

        for (int i = 0; i < maxIterations; i++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Agent request timed out after {}", requestTimeout);
                return new AgentResponse(
                        "Request timed out after " + requestTimeout.toSeconds() + "s.",
                        Collections.unmodifiableList(messages));
            }

            ChatResponse response = stream
                    ? llmClient.chatStream(model, messages, toolDefinitions, listener::onContent, listener::onReasoning)
                    : llmClient.chat(model, messages, toolDefinitions);

            // Guard against malformed/empty responses (null body, no choices, no message) so a
            // provider hiccup degrades to a graceful answer instead of an NPE/IndexOutOfBounds.
            ChatResponse.Choice choice = firstChoice(response);
            if (choice == null || choice.message() == null) {
                log.warn("LLM returned no usable choice on iteration {} (model={})", i, model);
                String answer = (lastAssistantContent != null && !lastAssistantContent.isBlank())
                        ? lastAssistantContent
                        : "The language model returned an empty response.";
                return new AgentResponse(answer, Collections.unmodifiableList(messages));
            }
            ChatMessage assistantMsg = choice.message();
            messages.add(assistantMsg);

            String finishReason = choice.finishReason();
            log.debug("Iteration {}: finish_reason={}, has_tool_calls={}",
                    i, finishReason, assistantMsg.toolCalls() != null);

            // Handle tool calls regardless of finishReason — some models set
            // finish_reason to "stop" or leave it null even when tool_calls are present.
            if (assistantMsg.toolCalls() != null && !assistantMsg.toolCalls().isEmpty()) {
                hadToolCalls = true;
                if (assistantMsg.content() != null && !assistantMsg.content().isEmpty()) {
                    lastAssistantContent = assistantMsg.content();
                }
                assistantMsg = normalizeToolCallAssistantMessage(assistantMsg);
                messages.set(messages.size() - 1, assistantMsg);
                for (ToolCall toolCall : assistantMsg.toolCalls()) {
                    listener.onToolCall(toolCall.function().name(), toolCall.function().arguments());
                    String toolResult = executeToolCall(toolCall, toolServerMap,
                            request.sessionId(), owner, request.agentId(), request.recentMessages());
                    listener.onToolResult(toolCall.function().name(), toolResult);
                    messages.add(ChatMessage.tool(toolCall.id(), toolResult));
                }
            } else {
                String answer = assistantMsg.content();
                // Some models (e.g. deepseek streaming) return an empty assistant message after
                // completing tool calls instead of following up with a text answer. Nudge once by
                // removing the empty message and asking the model to reply, then retry.
                if ((answer == null || answer.isBlank()) && hadToolCalls && !nudgedForEmptyResponse) {
                    messages.remove(messages.size() - 1);
                    nudgedForEmptyResponse = true;
                    messages.add(ChatMessage.user("Please provide your answer."));
                    log.debug("Empty response after tool calls on iteration {}; nudging model for text answer", i);
                    continue;
                }
                if (answer == null || answer.isBlank()) {
                    answer = lastAssistantContent;
                }
                if (answer == null || answer.isBlank()) {
                    answer = "The agent finished without producing a text answer.";
                }
                final String finalAnswer = answer;
                memoryService.ifPresent(memory -> memory.remember(memoryOwner(owner, request.agentId()),
                        request.prompt(), finalAnswer));
                if (!clientManagedContext) {
                    conversationMemory.ifPresent(cm -> cm.record(
                            sessionScope(owner, request.agentId(), request.sessionId()),
                            request.prompt(), finalAnswer));
                }
                return new AgentResponse(answer, Collections.unmodifiableList(messages));
            }
        }

        log.warn("Agent reached max iterations ({}) without a final answer", maxIterations);
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
        List<ToolDefinition> toolDefinitions = new ArrayList<>(localToolDefinitions);

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

    /** First choice of a chat response, or {@code null} if the response carries none. */
    private static ChatResponse.Choice firstChoice(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        return response.choices().get(0);
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

    private RoutingSelection resolveModel(AgentRequest request) {
        String fallbackModel = firstNonBlank(request.model(), defaultModel);
        String decisionModel = firstNonBlank(request.decision(), fallbackModel);
        String complexModel = firstNonBlank(request.complex(), fallbackModel);
        String simpleModel = firstNonBlank(request.simple(), fallbackModel);

        if (shouldSkipRouting(request, decisionModel, complexModel, simpleModel)) {
            return new RoutingSelection(fallbackModel, "legacy", decisionModel);
        }

        String route = classifyRequest(request, decisionModel);
        String selectedModel = "simple".equals(route) ? simpleModel : complexModel;
        return new RoutingSelection(selectedModel, route, decisionModel);
    }

    private String classifyRequest(AgentRequest request, String decisionModel) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(Prompts.ROUTING_DECISION));
        if (request.recentMessages() != null && !request.recentMessages().isEmpty()) {
            messages.add(ChatMessage.system("Recent context:\n" + String.join("\n", request.recentMessages())));
        }
        messages.add(ChatMessage.user(request.prompt()));

        try {
            ChatResponse response = llmClient.chat(decisionModel, messages, List.of());
            ChatResponse.Choice choice = firstChoice(response);
            String content = choice != null && choice.message() != null ? choice.message().content() : null;
            String route = parseRoutingChoice(content);
            if (route != null) {
                log.debug("Routing decision model selected '{}'", route);
                return route;
            }
            log.warn("Routing decision model returned an unparseable response: {}", content);
        } catch (Exception e) {
            log.warn("Routing decision model '{}' failed; defaulting to complex: {}", decisionModel, e.getMessage());
        }
        return "complex";
    }

    private static String parseRoutingChoice(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = ROUTING_PATTERN.matcher(content.toLowerCase(Locale.ROOT));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static boolean shouldSkipRouting(AgentRequest request, String decisionModel,
                                             String complexModel, String simpleModel) {
        if (request.decision() == null && request.complex() == null && request.simple() == null) {
            return true;
        }
        return Objects.equals(decisionModel, complexModel)
                && Objects.equals(decisionModel, simpleModel)
                && Objects.equals(complexModel, simpleModel);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String memoryOwner(String owner, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return owner;
        }
        if (owner == null || owner.isBlank()) {
            return agentId;
        }
        return owner + ":" + agentId;
    }

    private static String sessionScope(String owner, String agentId, String sessionId) {
        String base = memoryOwner(owner, agentId);
        if (sessionId == null || sessionId.isBlank()) {
            return base;
        }
        return base + ":" + sessionId;
    }

    private record RoutingSelection(String model, String route, String decisionModel) {}

    private String executeToolCall(ToolCall toolCall, Map<String, String> toolServerMap,
                                   String sessionId, String owner, String agentId, List<String> channelMessages) {
        String toolName = toolCall.function().name();
        Map<String, Object> args = parseArguments(toolCall.function().arguments());

        LocalTool localTool = localTools.find(toolName);
        if (localTool != null) {
            log.debug("Executing built-in tool '{}' with args: {}", toolName, args);
            ToolContext ctx = new ToolContext(
                    workspaceRegistry.resolve(sessionId), sessionId, owner, agentId, channelMessages);
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

    private static ChatMessage normalizeToolCallAssistantMessage(ChatMessage message) {
        if (message == null || message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return message;
        }
        return new ChatMessage(
                message.role(),
                message.content() == null ? "" : message.content(),
                message.reasoningContent() == null ? "" : message.reasoningContent(),
                message.toolCalls(),
                message.toolCallId());
    }

    private static String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return "global";
        }
        return owner;
    }
}
