package com.example.agent;

import com.example.agent.model.*;
import com.example.agent.prompts.Prompts;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.LocalToolRegistry;
import com.example.agent.tool.JustInTimeToolRegistry;
import com.example.agent.tool.ToolApprovalRequiredException;
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

    /** Token headroom reserved for the model's reasoning + answer when capping the live transcript. */
    private static final int IN_LOOP_RESPONSE_RESERVE_TOKENS = 8192;
    /** Fraction of the context window the live transcript may fill before it is compacted in-loop. */
    private static final double IN_LOOP_BUDGET_FRACTION = 0.90;
    /** A message needs at least this many characters of content/reasoning to be worth truncating. */
    private static final int TRUNCATABLE_MIN_CHARS = 1200;
    /** Lower bound on the characters kept from the head of an oversized message when truncating it. */
    private static final int TRUNCATED_HEAD_CHARS = 800;
    private static final String TRUNCATION_MARKER = "\n\n…[truncated to fit the model's context window]";

    /** Absolute ceiling on a client-requested tool-call cap, regardless of the configured default. */
    private static final int MAX_TOOL_CALL_CEILING = 200;

    /** Floor on a client-requested per-run timeout, so a request cannot starve itself. */
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Absolute ceiling on a client-requested per-run timeout, regardless of the configured default. */
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(30);

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
        return runLoop(request, NO_OP_LISTENER, false, "global", true, null, null, null);
    }

    /**
     * Compacts a transcript into a compact briefing the caller can use to seed a fresh, shorter
     * conversation. Runs a single non-streaming model call with {@link Prompts#COMPACT_CONVERSATION}
     * and no tools. Returns the summary text, or {@code null} if the model produced nothing usable.
     *
     * @param model        the model to summarize with; falls back to the configured default when blank
     * @param priorMessages the conversation turns to compress (oldest first)
     */
    public String summarizeForCompaction(String model, List<ChatMessage> priorMessages) {
        if (priorMessages == null || priorMessages.isEmpty()) {
            return null;
        }
        String summaryModel = firstNonBlank(model, defaultModel);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(Prompts.COMPACT_CONVERSATION));
        messages.add(ChatMessage.system("Conversation to compact:\n" + renderTranscript(priorMessages)));
        messages.add(ChatMessage.user("Produce the compacted briefing now."));
        ChatResponse response = llmClient.chat(summaryModel, messages, List.of());
        ChatResponse.Choice choice = firstChoice(response);
        if (choice == null || choice.message() == null) {
            return null;
        }
        String content = choice.message().content();
        return content == null || content.isBlank() ? null : content.trim();
    }

    /** Renders a transcript as plain role-tagged text for summarization input. */
    private static String renderTranscript(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String role = message.role() == null ? "assistant" : message.role();
            sb.append(role.toUpperCase(Locale.ROOT)).append(": ").append(message.content().trim()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Rough token estimate for a list of messages. Uses the common ~4-characters-per-token heuristic
     * plus a small per-message overhead for role framing, which is accurate enough to drive a
     * conservative compaction threshold without pulling in a model-specific tokenizer.
     */
    public static int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long chars = 0;
        int overhead = 0;
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            overhead += 4;
            if (message.content() != null) {
                chars += message.content().length();
            }
            if (message.reasoningContent() != null) {
                chars += message.reasoningContent().length();
            }
        }
        return (int) (chars / 4) + overhead;
    }

    /**
     * Caps the live transcript so the next provider call stays inside the model's context window. Only
     * the largest messages' content (and bulky prior reasoning) are shortened — messages are never
     * removed — so {@code tool_calls}/{@code tool} pairing and ordering stay intact. No-op when the
     * context window is unknown ({@code budgetTokens <= 0}) or the transcript already fits.
     */
    private void compactToFitContext(List<ChatMessage> messages, List<ToolDefinition> toolDefinitions,
                                     int budgetTokens) {
        if (budgetTokens <= 0 || messages.isEmpty()) {
            return;
        }
        int target = (int) (budgetTokens * IN_LOOP_BUDGET_FRACTION)
                - IN_LOOP_RESPONSE_RESERVE_TOKENS
                - estimateToolTokens(toolDefinitions);
        if (target <= 0 || estimateTokens(messages) <= target) {
            return;
        }
        int before = estimateTokens(messages);
        int guard = 0;
        while (estimateTokens(messages) > target && guard++ < 2000) {
            int idx = indexOfLargestTruncatable(messages);
            if (idx < 0) {
                break; // nothing left large enough to shorten without touching the current question
            }
            ChatMessage original = messages.get(idx);
            ChatMessage truncated = truncateMessage(original);
            if (sameBody(original, truncated)) {
                break;
            }
            messages.set(idx, truncated);
        }
        log.info("Compacted in-loop transcript from ~{} to ~{} tokens (context budget {}, target {})",
                before, estimateTokens(messages), budgetTokens, target);
    }

    /**
     * Index of the largest message whose body can still be meaningfully shortened, chosen by role
     * priority: bulky tool output first, then assistant turns, then earlier user turns, and only system
     * prompts as a last resort. The current (most recent) user message is never truncated.
     */
    private static int indexOfLargestTruncatable(List<ChatMessage> messages) {
        int lastUserIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                lastUserIndex = i;
                break;
            }
        }
        for (String role : new String[] {"tool", "assistant", "user", "system"}) {
            int best = -1;
            int bestLen = TRUNCATABLE_MIN_CHARS;
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                if (i == lastUserIndex || !role.equals(m.role())) {
                    continue;
                }
                int len = bodyLength(m);
                if (len > bestLen) {
                    best = i;
                    bestLen = len;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }

    /** Shortens a message: drops bulky prior reasoning first, then truncates the content head. */
    private static ChatMessage truncateMessage(ChatMessage message) {
        String reasoning = message.reasoningContent();
        String content = message.content();
        if (reasoning != null && reasoning.length() > TRUNCATED_HEAD_CHARS) {
            // Prior chain-of-thought is the least useful to retain verbatim — drop it outright. If that
            // alone makes a meaningful dent, leave the content untouched this pass.
            reasoning = null;
            if (content == null || content.length() <= TRUNCATABLE_MIN_CHARS) {
                return withBody(message, content, null);
            }
        }
        return withBody(message, truncateContent(content), reasoning);
    }

    private static String truncateContent(String content) {
        if (content == null) {
            return null;
        }
        int keep = Math.max(TRUNCATED_HEAD_CHARS, content.length() / 2);
        if (keep >= content.length()) {
            return content;
        }
        return content.substring(0, keep) + TRUNCATION_MARKER;
    }

    private static ChatMessage withBody(ChatMessage message, String content, String reasoning) {
        return new ChatMessage(message.role(), content, message.attachments(), reasoning,
                message.toolCalls(), message.toolCallId());
    }

    private static int bodyLength(ChatMessage message) {
        int len = 0;
        if (message.content() != null) {
            len += message.content().length();
        }
        if (message.reasoningContent() != null) {
            len += message.reasoningContent().length();
        }
        return len;
    }

    private static boolean sameBody(ChatMessage a, ChatMessage b) {
        return Objects.equals(a.content(), b.content())
                && Objects.equals(a.reasoningContent(), b.reasoningContent());
    }

    /** Rough token cost of the advertised tool schemas, which also count against the context window. */
    private int estimateToolTokens(List<ToolDefinition> toolDefinitions) {
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return 0;
        }
        try {
            return objectMapper.writeValueAsString(toolDefinitions).length() / 4;
        } catch (Exception e) {
            return toolDefinitions.size() * 120;
        }
    }

    /**
     * Streaming variant of {@link #chat}: assistant text is delivered token-by-token via
     * {@code listener} as it arrives from the model, and tool calls are reported as the loop runs
     * them. Returns the same {@link AgentResponse} (final answer plus full conversation history)
     * once the loop completes.
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener) {
        return runLoop(request, listener, true, "global", true, null, null, null);
    }

    public AgentResponse chat(AgentRequest request, String owner) {
        return runLoop(request, NO_OP_LISTENER, false, normalizeOwner(owner), hasSandbox(request), null, null, null);
    }

    /**
     * Non-streaming variant that caps this run's tool-call iterations to {@code maxToolCalls}. See
     * {@link #chatStream(AgentRequest, AgentStreamListener, String, Long, Integer)} for how the value
     * is resolved against the configured default and ceiling.
     */
    public AgentResponse chat(AgentRequest request, String owner, Integer maxToolCalls) {
        return runLoop(request, NO_OP_LISTENER, false, normalizeOwner(owner), hasSandbox(request), null, maxToolCalls, null);
    }

    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener, String owner) {
        return runLoop(request, listener, true, normalizeOwner(owner), hasSandbox(request), null, null, null);
    }

    /**
     * Streaming variant that also caps the live transcript to {@code contextLimit} tokens. As the loop
     * runs tool calls, their results accumulate in the message list and are re-sent on every iteration;
     * left unchecked a few large tool outputs can push a single turn past the model's context window and
     * the provider rejects the request (HTTP 400). When {@code contextLimit} is known, the oldest/largest
     * messages are truncated in place before each call so the run stays within budget. A {@code null}
     * limit disables the in-loop cap (preserving the previous behaviour for callers without a window).
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener, String owner,
                                    Long contextLimit) {
        return runLoop(request, listener, true, normalizeOwner(owner), hasSandbox(request), contextLimit, null, null);
    }

    /**
     * Streaming variant that also caps the number of tool-call iterations for this run to
     * {@code maxToolCalls}. A {@code null}, zero, or negative value falls back to the server default
     * ({@code agent.max-iterations}); larger values are bounded by {@link #MAX_TOOL_CALL_CEILING} so a
     * client request can lower or raise the limit within a safe range.
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener, String owner,
                                    Long contextLimit, Integer maxToolCalls) {
        return runLoop(request, listener, true, normalizeOwner(owner), hasSandbox(request), contextLimit,
                maxToolCalls, null);
    }

    /**
     * Streaming variant that also overrides this run's wall-clock timeout with
     * {@code requestTimeoutSeconds}. A {@code null}, zero, or negative value falls back to the server
     * default ({@code agent.request-timeout}); other values are bounded to
     * [{@link #MIN_REQUEST_TIMEOUT}, {@link #MAX_REQUEST_TIMEOUT}] so a client cannot disable the
     * deadline or starve its own run.
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamListener listener, String owner,
                                    Long contextLimit, Integer maxToolCalls, Integer requestTimeoutSeconds) {
        return runLoop(request, listener, true, normalizeOwner(owner), hasSandbox(request), contextLimit,
                maxToolCalls, requestTimeoutSeconds);
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
                                  String owner, boolean includeWorkspaceTools, Long contextLimit,
                                  Integer maxToolCalls, Integer requestTimeoutSeconds) {
        Duration timeout = resolveTimeout(requestTimeoutSeconds);
        Instant deadline = Instant.now().plus(timeout);
        int iterationCap = resolveIterationCap(maxToolCalls);
        int contextBudget = contextLimit == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, contextLimit));
        RoutingSelection routing = resolveModel(request);
        String model = routing.model();
        // A sandbox-scoped request resolves its workspace (and tool execution context) from the
        // sandbox id; otherwise fall back to the session id, preserving the previous behaviour.
        String workspaceKey = firstNonBlank(request.sandboxId(), request.sessionId());
        Workspace workspace = workspaceRegistry.resolve(workspaceKey);
        // Host-backed agent sessions (the "Agent" mode rooted at ~/) register a workspace by session id
        // but carry no sandbox, so grant them filesystem/shell tools when a workspace is registered.
        boolean workspaceTools = includeWorkspaceTools || workspaceRegistry.isRegistered(workspaceKey);
        List<ToolDefinition> initialToolDefinitions = collectTools(workspace, workspaceTools);

        log.debug("Agent chat: model={}, route={}, decisionModel={}, tools available={} (local={}), "
                        + "workspaceTools={}, stream={}",
                model, routing.route(), routing.decisionModel(), initialToolDefinitions.size(),
                localTools.tools().size(), workspaceTools, stream);

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
        int imageAttachmentCount = imageAttachmentCount(request.attachments());
        if (imageAttachmentCount > 0 && advertisesTool(initialToolDefinitions, "describe_image")) {
            messages.add(ChatMessage.system(Prompts.imageAttachmentNotice(imageAttachmentCount)));
        }
        workspaceRegistry.githubRepo(request.sandboxId()).ifPresent(repoFullName ->
                messages.add(ChatMessage.system(Prompts.githubRepoContext(repoFullName))));
        if (workspaceTools) {
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

        for (int i = 0; i < iterationCap; i++) {
            ensureNotCancelled();
            if (Instant.now().isAfter(deadline)) {
                log.warn("Agent request timed out after {}", timeout);
                return new AgentResponse(
                        "Request timed out after " + timeout.toSeconds() + "s.",
                        Collections.unmodifiableList(messages));
            }

            // Rebuild the tool list on every loop iteration so freshly written local manifests
            // become visible without a service restart.
            List<ToolDefinition> toolDefinitions = collectTools(workspace, workspaceTools);

            // Keep the growing transcript (prior turns + this turn's accumulating tool results) within
            // the model's context window so the provider does not reject the next call for being too long.
            compactToFitContext(messages, toolDefinitions, contextBudget);

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
                    listener.onToolCall(toolCall.id(), toolCall.function().name(), toolCall.function().arguments());
                    String toolResult;
                    try {
                        toolResult = executeToolCall(toolCall,
                                workspace, request.sessionId(), owner, request.agentId(), request.recentMessages(),
                                request.sandboxId(), request.attachments());
                    } catch (ToolApprovalRequiredException approval) {
                        // The tool needs user verification before it can act. Tag the call so the chat
                        // layer can resolve its tool card, then unwind the loop: the run pauses here and
                        // resumes (or not) once the user approves or declines.
                        approval.attachToolCall(toolCall.id(), toolCall.function().name());
                        throw approval;
                    }
                    listener.onToolResult(toolCall.id(), toolCall.function().name(), toolResult);
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

        log.warn("Agent reached max iterations ({}) without a final answer", iterationCap);
        return new AgentResponse(
                "Reached the maximum number of tool-call iterations without a final answer.",
                Collections.unmodifiableList(messages));
    }

    /**
     * Resolves the per-run tool-call iteration cap. A blank/non-positive request value uses the
     * configured server default ({@code agent.max-iterations}); otherwise the requested value is used,
     * bounded to [1, {@link #MAX_TOOL_CALL_CEILING}] so a client cannot drive an unbounded loop.
     */
    private int resolveIterationCap(Integer maxToolCalls) {
        if (maxToolCalls == null || maxToolCalls <= 0) {
            return maxIterations;
        }
        return Math.min(maxToolCalls, MAX_TOOL_CALL_CEILING);
    }

    /**
     * Resolves the per-run wall-clock timeout. A blank/non-positive request value uses the configured
     * server default ({@code agent.request-timeout}); otherwise the requested value (in seconds) is used,
     * bounded to [{@link #MIN_REQUEST_TIMEOUT}, {@link #MAX_REQUEST_TIMEOUT}] so a client cannot disable
     * the deadline or starve its own run.
     */
    private Duration resolveTimeout(Integer requestTimeoutSeconds) {
        if (requestTimeoutSeconds == null || requestTimeoutSeconds <= 0) {
            return requestTimeout;
        }
        Duration requested = Duration.ofSeconds(requestTimeoutSeconds);
        if (requested.compareTo(MIN_REQUEST_TIMEOUT) < 0) {
            return MIN_REQUEST_TIMEOUT;
        }
        if (requested.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            return MAX_REQUEST_TIMEOUT;
        }
        return requested;
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

    /** Number of image attachments (mime type {@code image/*}) carried by a request. */
    private static int imageAttachmentCount(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ChatAttachment attachment : attachments) {
            if (attachment != null
                    && attachment.dataUrl() != null
                    && !attachment.dataUrl().isBlank()
                    && (attachment.mimeType() == null
                        || attachment.mimeType().toLowerCase(Locale.ROOT).startsWith("image/"))) {
                count++;
            }
        }
        return count;
    }

    /** Whether the advertised tool set includes a tool with the given name. */
    private static boolean advertisesTool(List<ToolDefinition> toolDefinitions, String name) {
        if (toolDefinitions == null) {
            return false;
        }
        for (ToolDefinition definition : toolDefinitions) {
            if (definition.function() != null && name.equals(definition.function().name())) {
                return true;
            }
        }
        return false;
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
                                   String sandboxId, List<ChatAttachment> attachments) {
        String toolName = toolCall.function().name();
        Map<String, Object> args = parseArguments(toolCall.function().arguments());

        LocalTool localTool = localTools.find(toolName);
        if (localTool == null) {
            localTool = jitTools.find(toolName, workspace);
        }
        if (localTool != null) {
            log.debug("Executing built-in tool '{}' with args: {}", toolName, args);
            ToolContext ctx = new ToolContext(
                    workspace, sessionId, owner, agentId, channelMessages, sandboxId, attachments);
            try {
                return localTool.execute(args, ctx);
            } catch (ToolApprovalRequiredException e) {
                // Propagate: this is a control signal (pause for user approval), not a tool failure.
                throw e;
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
