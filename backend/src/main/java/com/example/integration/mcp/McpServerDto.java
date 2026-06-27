package com.example.integration.mcp;

import java.time.Instant;
import java.util.List;

/**
 * API view of an MCP server. Crucially this NEVER includes the bearer token, OAuth tokens, or client
 * secret — only {@code hasToken}/{@code oauthConnected} report whether credentials are stored, so a
 * secret can never be read back by any client.
 */
public record McpServerDto(
        String id,
        String name,
        String displayName,
        String transport,
        String endpointUrl,
        String authType,
        boolean enabled,
        boolean hasToken,
        boolean oauthConnected,
        boolean needsAuthorization,
        String command,
        int toolCount,
        int enabledToolCount,
        Instant createdAt,
        Instant updatedAt,
        List<McpToolDto> tools) {

    public static McpServerDto from(McpServerEntity server, List<McpServerToolEntity> tools) {
        return from(server, tools, McpServerConfig.empty());
    }

    public static McpServerDto from(McpServerEntity server, List<McpServerToolEntity> tools,
                                    McpServerConfig config) {
        List<McpToolDto> toolDtos = tools.stream().map(McpToolDto::from).toList();
        int enabledCount = (int) tools.stream().filter(t -> t.enabled() && !t.stale()).count();
        boolean oauthConnected = server.authType() == McpAuthType.OAUTH
                && config.oauth() != null && config.oauth().hasTokens();
        boolean needsAuthorization = server.authType() == McpAuthType.OAUTH && !oauthConnected;
        String command = config.stdio() == null ? null : config.stdio().command();
        return new McpServerDto(
                server.id(),
                server.name(),
                server.displayName(),
                server.transport().name(),
                server.endpointUrl(),
                server.authType().name(),
                server.enabled(),
                server.hasToken(),
                oauthConnected,
                needsAuthorization,
                command,
                tools.size(),
                enabledCount,
                server.createdAt(),
                server.updatedAt(),
                toolDtos);
    }
}
