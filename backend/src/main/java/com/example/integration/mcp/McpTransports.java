package com.example.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.example.integration.mcp.McpHttpClient.McpClientException;

/**
 * Opens an {@link McpSession} for a server using the right transport.
 *
 * <p>This is the single place that knows how each {@link McpTransport} connects, so the connection,
 * discovery, and invocation services stay transport-agnostic. Credentials are resolved separately
 * (see {@link McpCredentialResolver}) and passed in, keeping this router free of secret-handling.
 */
@Component
public class McpTransports {

    private final McpHttpClient httpClient;
    private final McpStdioClient stdioClient;
    private final ObjectMapper objectMapper;

    public McpTransports(McpHttpClient httpClient, McpStdioClient stdioClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.stdioClient = stdioClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Opens and initializes a session. {@code bearerToken} (a static token or a resolved OAuth access
     * token) is attached for network transports and ignored for stdio.
     */
    public McpSession openSession(McpServerEntity server, String bearerToken) throws McpClientException {
        return switch (server.transport()) {
            case STREAMABLE_HTTP -> {
                if (server.endpointUrl() == null || server.endpointUrl().isBlank()) {
                    throw new McpClientException("HTTP MCP server has no endpoint URL.");
                }
                yield httpClient.newSession(server.endpointUrl(), bearerToken);
            }
            case STDIO -> {
                McpServerConfig.Stdio stdio = McpServerConfig.parse(objectMapper, server.configJson()).stdio();
                if (stdio == null || stdio.command() == null || stdio.command().isBlank()) {
                    throw new McpClientException("stdio MCP server has no command configured.");
                }
                yield stdioClient.newSession(stdio.command(), stdio.args(), stdio.env());
            }
        };
    }

    public boolean stdioEnabled() {
        return stdioClient.isEnabled();
    }
}
