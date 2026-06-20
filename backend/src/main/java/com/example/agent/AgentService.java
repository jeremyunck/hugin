package com.example.agent;

import com.example.agent.model.*;
import com.example.agent.prompts.Prompts;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.LocalToolRegistry;
import com.example.agent.tool.JustInTimeToolRegistry;
import com.example.agent.tool.ToolContext;
import com.example.agent.tool.Workspace;
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
 *   <li>Collects every built-in local tool plus any just-in-time tools in the workspace.</li>
 *   <li>Sends the user prompt to the configured LLM with those tools advertised.</li>
 *   <li>When the model requests a tool call, runs it in-process.</li>
 *   <li>Feeds the tool result back and repeats until the model produces a final answer.</li>
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final Pattern ROUTING_PATTERN = Pattern.compile("\\b(simple|complex)\\b");
    /** How long a routing decision is reused for an identical prompt + context. */
    private static final Duration ROUTING_CACHE_TTL = Duration.ofSeconds(30);
    private static final int ROUTING_CACHE_MAX_ENTRIES = 256;
    private final int maxIterations;

    private final OpenAiClient llmClient;
    private final LocalToolRegistry localTools;
    private final JustInTimeToolRegistry jitTools;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final String defaultModel;
    private final WorkspaceRegistry workspaceRegistry;
    private final Optional<MemoryService> memoryService;
    private final Optional<ConversationMemoryService> conversationMemory;
    private final Optional<SystemFactsService> systemFactsService;
    private final Optional<StartupAnnouncementService> startupAnnouncement;
    /** Names of every built-in local tool, used to detect just-in-time tools that shadow them. */
    private final Set<String> builtinToolNames;
    private final Map<String, CachedRoute> routingCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedRoute(String route, Instant expiresAt) {}

    public AgentService(
            OpenAiClient llmClient,
            LocalToolRegistry localTools,
            JustInTimeToolRegistry jitTools,
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
        this.localTools = localTools;
        this.jitTools = jitTools;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.defaultModel = defaultModel;
        this.maxIterations = maxIterations;
        this.workspaceRegistry = workspaceRegistry;
        this.memoryService = memoryService;
        this.conversationMemory = conversationMemory;
        this.systemFactsService = systemFactsService;
        this.startupAnnouncement = startupAnnouncement;
        Set<String> names = new LinkedHashSet<>();
        for (LocalTool tool : localTools.tools()) {
            names.add(tool.name());
        }
        this.builtinToolNames = Set.copyOf(names);
    }

    public record ToolSummary(String name, String description, String server, String transport) {}

    /** Returns the tools available to the default (host) workspace, including filesystem tools. */
    public List<ToolSummary> availableTools() {
        return availableTools(null);
    }

    /**
     * Returns the tools the agent would advertise for a request bound to {@code sandboxId}.
     *
     * <p>The list is dynamic: filesystem/shell tools (and just-in-time workspace tools) are only
     * included when the request has a sandbox to operate in, and integration-backed tools are only
     * included when their integration reports itself available. A blank {@code sandboxId} reflects a
     * "pure chat" request (no workspace), so workspace tools are omitted.
     */
    public List<ToolSummary> availableTools(String sandboxId) {
        boolean includeWorkspaceTools = sandboxId != null && !sandboxId.isBlank();
        Workspace workspace = workspaceRegistry.resolve(sandboxId);
        List<ToolSummary> result = new ArrayList<>();
        Set<String> toolNames = new LinkedHashSet<>();
        for (LocalTool tool : localTools.tools()) {
            if (!tool.isAvailable()) {
                continue;
            }
            if (tool.requiresWorkspace() && !includeWorkspaceTools) {
                continue;
            }
            result.add(new ToolSummary(tool.name(), tool.description(), "local", "built-in"));
            toolNames.add(tool.name());
        }
        if (includeWorkspaceTools) {
            for (LocalTool tool : jitTools.tools(workspace)) {
                if (toolNames.add(tool.name())) {
                    result.add(new ToolSummary(tool.name(), tool.description(), "workspace", "jit"));
                }
            }
        }
        return result;
    }

    public AgentResponse chat(AgentRequest request) {
        return runLoop(request, NO_OP_LISTENER, false, "global", true);
    }

    /**
     * Streaming variant of {@link #chat}: assistant text is delivered token-by-token via
     * {@code listener} as it arrives from the model, and tool calls are reported as the loop runs
     * them. Returns the same {@link AgentResponse} (final answer plus full conversation history)
     * once the loop completes.
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener) {
        return runLoop(request, listener, true, "global", true);
    }

    public AgentResponse chat(AgentRequest request, String owner) {
        return runLoop(request, NO_OP_LISTENER, false, normalizeOwner(owner), hasSandbox(request));
    }

    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener, String owner) {
        return runLoop(request, listener, true, normalizeOwner(owner), hasSandbox(request));
    }

    public List<ChatMessage> history(String owner, String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return conversationMemory
                .map(cm -> cm.history(sessionScope(normalizeOwner(owner), agentId, sessionId)))
                .orElse(List.of());
    }

    /** Forgets the stored conversation history for a session, scoped to its owner/agent. */
    public void deleteHistory(String owner, String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        conversationMemory.ifPresent(cm ->
                cm.delete(sessionScope(normalizeOwner(owner), agentId, sessionId)));
    }

    /** Whether a request is bound to a sandbox, which gives it filesystem/shell tools. */
    private static boolean hasSandbox(AgentRequest request) {
        return request != null && request.sandboxId() != null && !request.sandboxId().isBlank();
    }

    private AgentResponse runLoop(AgentRequest request, AgentStreamListener listener, boolean stream,
                                  String owner, boolean includeWorkspaceTools) {
        Instant deadline = Instant.now().plus(requestTimeout);
        RoutingSelection routing = resolveModel(request);
        String model = routing.model();
        // A sandbox-scoped request resolves its workspace (and tool execution context) from the
        // sandbox id; otherwise fall back to the session id, preserving the previous behaviour.
        String workspaceKey = firstNonBlank(request.sandboxId(), request.sessionId());
        Workspace workspace = workspaceRegistry.resolve(workspaceKey);
        List<ToolDefinition> initialToolDefinitions = collectTools(workspace, includeWorkspaceTools);

        log.debug("Agent chat: model={}, route={}, decisionModel={}, tools available={} (local={}), "
                        + "workspaceTools={}, stream={}",
                model, routing.route(), routing.decisionModel(), initialToolDefinitions.size(),
                localTools.tools().size(), includeWorkspaceTools, stream);

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
        if (!initialToolDefinitions.isEmpty()) {
            messages.add(ChatMessage.system(Prompts.TOOL_USE));
        }
        workspaceRegistry.githubRepo(request.sandboxId()).ifPresent(repoFullName ->
                messages.add(ChatMessage.system(Prompts.githubRepoContext(repoFullName))));
        if (includeWorkspaceTools) {
            String skillPrompt = Prompts.workspaceSkills(WorkspaceSkills.list(workspace));
            if (!skillPrompt.isBlank()) {
                messages.add(ChatMessage.system(skillPrompt));
            }
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
        boolean clientManagedContext = isClientManagedContext(request);
        if (request.priorMessages() != null && !request.priorMessages().isEmpty()) {
            messages.addAll(sanitizeReplayMessages(request.priorMessages()));
        } else if (!clientManagedContext) {
            // Replay the recent turns of this session (short-term memory) before the current prompt.
            conversationMemory.ifPresent(cm -> messages.addAll(cm.history(
                    sessionScope(owner, request.agentId(), request.sessionId()))));
        }
        ChatMessage userMessage = ChatMessage.user(request.prompt(), request.attachments());
        messages.add(userMessage);
        int turnStartIndex = messages.size() - 1;

        String lastAssistantContent = null;
        boolean hadToolCalls = false;
        boolean nudgedForEmptyResponse = false;

        for (int i = 0; i < maxIterations; i++) {
            ensureNotCancelled();
            if (Instant.now().isAfter(deadline)) {
                log.warn("Agent request timed out after {}", requestTimeout);
                return new AgentResponse(
                        "Request timed out after " + requestTimeout.toSeconds() + "s.",
                        Collections.unmodifiableList(messages));
            }

            // Rebuild the tool list on every loop iteration so freshly written local manifests
            // become visible without a service restart.
            List<ToolDefinition> toolDefinitions = collectTools(workspace, includeWorkspaceTools);

            ChatResponse response;
            try {
                response = stream
                        ? (request.reasoningEffort() == null || request.reasoningEffort().isBlank()
                            ? llmClient.chatStream(model, messages, toolDefinitions, listener::onContent, listener::onReasoning)
                            : llmClient.chatStream(model, request.reasoningEffort(), messages, toolDefinitions, listener::onContent, listener::onReasoning))
                        : (request.reasoningEffort() == null || request.reasoningEffort().isBlank()
                            ? llmClient.chat(model, messages, toolDefinitions)
                            : llmClient.chat(model, request.reasoningEffort(), messages, toolDefinitions));
            } catch (RuntimeException e) {
                throw maybeCancellation(e);
            }

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
                    ensureNotCancelled();
                    listener.onToolCall(toolCall.function().name(), toolCall.function().arguments());
                    String toolResult = executeToolCall(toolCall,
                            workspace, request.sessionId(), owner, request.agentId(), request.recentMessages(),
                            request.sandboxId());
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
                    List<ChatMessage> transcript = new ArrayList<>();
                    boolean recordedUser = false;
                    for (int j = turnStartIndex; j < messages.size(); j++) {
                        ChatMessage message = messages.get(j);
                        if ("user".equals(message.role())) {
                            if (!recordedUser) {
                                transcript.add(message);
                                recordedUser = true;
                            }
                            continue;
                        }
                        if ("assistant".equals(message.role()) || "tool".equals(message.role())) {
                            transcript.add(message);
                        }
                    }
                    conversationMemory.ifPresent(cm -> cm.recordMessages(
                            sessionScope(owner, request.agentId(), request.sessionId()),
                            transcript));
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
     * Flattens the built-in local tools plus any just-in-time workspace tools into one OpenAI-format
     * list, filtered to what the request can actually use. Built-in local tools are advertised first
     * and take precedence on name collisions.
     *
     * <p>Two filters make the advertised tool set dynamic:
     * <ul>
     *   <li>An integration-backed tool that reports {@link LocalTool#isAvailable()} {@code false}
     *       (e.g. web search with no API key, Google Workspace not connected) is omitted entirely, so
     *       the model never sees a capability the user has not set up.</li>
     *   <li>A tool that {@link LocalTool#requiresWorkspace() requires a workspace} (filesystem/shell
     *       access), and the just-in-time workspace tools, are only included when
     *       {@code includeWorkspaceTools} is set — i.e. the request is bound to a sandbox. Pure chat
     *       requests therefore get a leaner tool set without filesystem tools.</li>
     * </ul>
     */
    private List<ToolDefinition> collectTools(Workspace workspace, boolean includeWorkspaceTools) {
        List<ToolDefinition> toolDefinitions = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (LocalTool tool : localTools.tools()) {
            if (!tool.isAvailable()) {
                continue;
            }
            if (tool.requiresWorkspace() && !includeWorkspaceTools) {
                continue;
            }
            toolDefinitions.add(ToolDefinition.from(tool.name(), tool.description(), tool.inputSchema()));
            names.add(tool.name());
        }
        if (includeWorkspaceTools) {
            for (LocalTool tool : jitTools.tools(workspace)) {
                if (builtinToolNames.contains(tool.name())) {
                    log.warn("JIT tool '{}' in workspace {} is shadowed by a built-in local tool",
                            tool.name(), workspace.root());
                    continue;
                }
                if (names.add(tool.name())) {
                    toolDefinitions.add(ToolDefinition.from(tool.name(), tool.description(), tool.inputSchema()));
                }
            }
        }
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
        // Identical prompt + context within the TTL gets the cached route, skipping a whole
        // decision-model round trip (retries, stream + non-stream pairs, repeated commands).
        String cacheKey = routingCacheKey(request, decisionModel);
        CachedRoute cached = routingCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            log.debug("Routing decision '{}' served from cache", cached.route());
            return cached.route();
        }

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
                cacheRoute(cacheKey, route);
                return route;
            }
            log.warn("Routing decision model returned an unparseable response: {}", content);
        } catch (Exception e) {
            log.warn("Routing decision model '{}' failed; defaulting to complex: {}", decisionModel, e.getMessage());
        }
        // The fallback after a failure is deliberately not cached so a transient error doesn't
        // pin requests to the complex model for the TTL.
        return "complex";
    }

    private void cacheRoute(String key, String route) {
        if (routingCache.size() >= ROUTING_CACHE_MAX_ENTRIES) {
            Instant now = Instant.now();
            routingCache.values().removeIf(e -> !e.expiresAt().isAfter(now));
            if (routingCache.size() >= ROUTING_CACHE_MAX_ENTRIES) {
                routingCache.clear();
            }
        }
        routingCache.put(key, new CachedRoute(route, Instant.now().plus(ROUTING_CACHE_TTL)));
    }

    private static String routingCacheKey(AgentRequest request, String decisionModel) {
        StringBuilder key = new StringBuilder(decisionModel).append('\0').append(request.prompt());
        if (request.priorMessages() != null && !request.priorMessages().isEmpty()) {
            for (ChatMessage message : sanitizeReplayMessages(request.priorMessages())) {
                key.append('\0').append(message.role()).append('\0').append(message.content());
            }
        }
        if (request.recentMessages() != null && !request.recentMessages().isEmpty()) {
            for (String message : request.recentMessages()) {
                key.append('\0').append(message);
            }
        }
        return key.toString();
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

    private static boolean isClientManagedContext(AgentRequest request) {
        if (request == null) {
            return false;
        }
        if (Boolean.TRUE.equals(request.clientManagedContext())) {
            return true;
        }
        String sessionId = request.sessionId();
        return request.recentMessages() != null
                && sessionId != null
                && (sessionId.startsWith("discord-channel-") || sessionId.startsWith("discord-dm-"));
    }

    private static List<ChatMessage> sanitizeReplayMessages(List<ChatMessage> priorMessages) {
        List<ChatMessage> sanitized = new ArrayList<>(priorMessages.size());
        for (ChatMessage message : priorMessages) {
            if (message == null || message.role() == null) {
                continue;
            }
            if ("user".equals(message.role())) {
                sanitized.add(ChatMessage.user(message.content(), message.attachments()));
            } else if ("assistant".equals(message.role())) {
                sanitized.add(ChatMessage.assistant(message.content(), message.reasoningContent()));
            }
        }
        return sanitized;
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

    private String executeToolCall(ToolCall toolCall,
                                   Workspace workspace,
                                   String sessionId, String owner, String agentId, List<String> channelMessages,
                                   String sandboxId) {
        String toolName = toolCall.function().name();
        Map<String, Object> args = parseArguments(toolCall.function().arguments());

        LocalTool localTool = localTools.find(toolName);
        if (localTool == null) {
            localTool = jitTools.find(toolName, workspace);
        }
        if (localTool != null) {
            log.debug("Executing built-in tool '{}' with args: {}", toolName, args);
            ToolContext ctx = new ToolContext(
                    workspace, sessionId, owner, agentId, channelMessages, sandboxId);
            try {
                return localTool.execute(args, ctx);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentRunCancelledException("Request cancelled.", e);
            } catch (Exception e) {
                String msg = "Tool call failed: " + e.getMessage();
                log.error(msg, e);
                return msg;
            }
        }

        String msg = "Tool '" + toolName + "' is not available as a built-in or workspace tool.";
        log.warn(msg);
        return msg;
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
                message.attachments(),
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

    private static void ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AgentRunCancelledException("Request cancelled.");
        }
    }

    private static RuntimeException maybeCancellation(RuntimeException e) {
        if (e instanceof AgentRunCancelledException cancelled) {
            return cancelled;
        }
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new AgentRunCancelledException("Request cancelled.", e);
            }
            cause = cause.getCause();
        }
        if (Thread.currentThread().isInterrupted()) {
            return new AgentRunCancelledException("Request cancelled.", e);
        }
        return e;
    }
}
