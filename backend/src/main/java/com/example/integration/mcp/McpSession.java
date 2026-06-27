package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A live, initialized MCP connection to one server, independent of transport.
 *
 * <p>A session has already completed the {@code initialize} handshake when handed back, so
 * {@link #serverInfo()} is available immediately and {@link #listTools()} / {@link #callTool} reuse the
 * same connection without re-handshaking. The HTTP transport reuses the negotiated {@code Mcp-Session-Id};
 * the stdio transport keeps the child process alive. {@link McpSessionManager} caches sessions for the
 * hot tool-call path; one-shot callers (discovery, connectivity test) open a session, use it, and
 * {@link #close()} it.
 */
public interface McpSession extends AutoCloseable {

    /** The server's {@code initialize} result (serverInfo, capabilities, protocolVersion). */
    JsonNode serverInfo();

    /** Lists the server's tools via {@code tools/list}. */
    List<McpHttpClient.DiscoveredTool> listTools() throws McpHttpClient.McpClientException;

    /** Invokes {@code tools/call} and returns concise text output. */
    String callTool(String name, Map<String, Object> arguments) throws McpHttpClient.McpClientException;

    /** Releases the session (HTTP DELETE / process termination). Never throws. */
    @Override
    void close();
}
