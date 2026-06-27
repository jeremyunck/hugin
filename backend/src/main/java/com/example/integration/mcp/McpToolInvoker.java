package com.example.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes an MCP tool call on behalf of an authenticated user and records an audit entry.
 *
 * <p>Given the model-facing {@code hugin_tool_name}, the invoker:
 * <ol>
 *   <li>looks up the tool and its owning server,</li>
 *   <li>enforces owner isolation and enabled/stale gating (a tool the caller doesn't own, or that is
 *       disabled/stale/on a disabled server, is treated as simply unavailable),</li>
 *   <li>resolves the server's credential and performs {@code tools/call} on a reused session (see
 *       {@link McpSessionManager}), transparently for HTTP, stdio, and OAuth servers,</li>
 *   <li>writes an {@link McpAuditLogEntity} (success or error), and</li>
 *   <li>returns a concise, LLM-readable text result — never a stack trace.</li>
 * </ol>
 */
@Service
public class McpToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(McpToolInvoker.class);
    private static final int RESULT_PREVIEW_LIMIT = 4000;
    private static final int ARGS_PREVIEW_LIMIT = 4000;

    private final McpServerToolRepository toolRepository;
    private final McpServerRepository serverRepository;
    private final McpCredentialResolver credentialResolver;
    private final McpSessionManager sessionManager;
    private final McpAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public McpToolInvoker(McpServerToolRepository toolRepository,
                          McpServerRepository serverRepository,
                          McpCredentialResolver credentialResolver,
                          McpSessionManager sessionManager,
                          McpAuditLogRepository auditLogRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.toolRepository = toolRepository;
        this.serverRepository = serverRepository;
        this.credentialResolver = credentialResolver;
        this.sessionManager = sessionManager;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Invokes the MCP tool advertised as {@code huginToolName} for {@code owner}. Returns a text result
     * suitable to feed straight back to the model, including for the failure cases.
     */
    public String invoke(String owner, String huginToolName, Map<String, Object> arguments,
                         String agentId, String sessionId) {
        Optional<McpServerToolEntity> toolOpt = toolRepository.findByHuginToolName(huginToolName);
        if (toolOpt.isEmpty()) {
            return "MCP tool '" + huginToolName + "' is not available.";
        }
        McpServerToolEntity tool = toolOpt.get();

        // Owner isolation: resolve the server only within this owner's scope. If it isn't theirs, treat
        // the tool as simply unavailable rather than revealing another user's server exists.
        Optional<McpServerEntity> serverOpt = serverRepository.findByIdAndOwner(tool.serverId(), owner);
        if (serverOpt.isEmpty()) {
            log.warn("Owner {} attempted to invoke MCP tool {} they do not own; denied.", owner, huginToolName);
            return "MCP tool '" + huginToolName + "' is not available.";
        }
        McpServerEntity server = serverOpt.get();

        if (!server.enabled() || !tool.enabled() || tool.stale()) {
            return "MCP tool '" + huginToolName + "' is currently disabled.";
        }

        String argsPreview = previewArguments(arguments);
        String bearerToken = credentialResolver.resolveBearer(server);
        try {
            String result = sessionManager.callTool(server, bearerToken, tool.toolName(), arguments);
            recordAudit(owner, agentId, sessionId, server.id(), tool.toolName(), argsPreview,
                    result, McpAuditLogEntity.STATUS_SUCCESS);
            return result == null || result.isBlank() ? "(the tool returned no content)" : result;
        } catch (McpHttpClient.McpClientException e) {
            String message = "MCP tool '" + huginToolName + "' failed: " + e.getMessage();
            recordAudit(owner, agentId, sessionId, server.id(), tool.toolName(), argsPreview,
                    message, McpAuditLogEntity.STATUS_ERROR);
            return message;
        }
    }

    private void recordAudit(String owner, String agentId, String sessionId, String serverId,
                             String toolName, String argsPreview, String result, String status) {
        try {
            auditLogRepository.insert(new McpAuditLogEntity(
                    UUID.randomUUID().toString(),
                    owner,
                    agentId,
                    sessionId,
                    serverId,
                    toolName,
                    argsPreview,
                    truncate(result, RESULT_PREVIEW_LIMIT),
                    status,
                    Instant.now(clock)));
        } catch (RuntimeException e) {
            // Auditing must never break a tool call; log and continue.
            log.warn("Failed to write MCP audit log for tool {} (owner {}): {}", toolName, owner, e.getMessage());
        }
    }

    private String previewArguments(Map<String, Object> arguments) {
        try {
            return truncate(objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments),
                    ARGS_PREVIEW_LIMIT);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…[truncated]";
    }
}
