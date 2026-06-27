package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal client for the MCP Streamable HTTP transport.
 *
 * <p>Speaks JSON-RPC 2.0 over HTTP POST and implements the three calls Hugin needs: {@code initialize},
 * {@code tools/list}, and {@code tools/call}. Each logical operation runs {@code initialize} first (as
 * the spec requires) and then the real call, carrying any {@code Mcp-Session-Id} the server returns
 * between the two requests.
 *
 * <p>Responses are accepted as either {@code application/json} or {@code text/event-stream} (SSE),
 * since Streamable HTTP servers may reply with either. Network failures, HTTP errors, timeouts,
 * invalid JSON, and malformed MCP envelopes are all converted into a {@link McpClientException} with a
 * concise, LLM-safe message — never a stack trace.
 *
 * <p>Hardening: connect/read timeouts are bounded and the response body is capped at
 * {@link #maxResponseBytes} bytes to avoid unbounded memory use from a hostile or buggy server.
 *
 * <p>Sessions: {@link #openSession} performs the handshake once and returns a {@link Connection}
 * carrying the negotiated {@code Mcp-Session-Id}; {@link #listToolsReusing}/{@link #callToolReusing}
 * then reuse it without re-initializing. {@link McpSessionManager} caches these across calls. A server
 * that has forgotten the session answers HTTP 404, which surfaces as {@link McpSessionExpiredException}
 * so the manager can transparently re-open and retry.
 *
 * <p>TODO(sessions): we still do not open a long-lived GET stream for server-initiated messages
 * (server→client notifications/sampling). That is only needed for servers that push to the client,
 * which Hugin does not yet consume.
 */
@Component
public class McpHttpClient {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClient.class);

    /** MCP protocol revision Hugin advertises. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final long maxResponseBytes;
    private final AtomicLong requestIds = new AtomicLong(1);

    public McpHttpClient(
            ObjectMapper objectMapper,
            @Value("${mcp.http.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${mcp.http.request-timeout-seconds:30}") long requestTimeoutSeconds,
            @Value("${mcp.http.max-response-bytes:2097152}") long maxResponseBytes) {
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxResponseBytes = maxResponseBytes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                // Don't auto-follow redirects: an MCP endpoint that 30x-redirects is misconfigured, and
                // following could leak the Authorization header to an unexpected host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Carries the per-operation connection details and the negotiated session id (if any). */
    public static final class Connection {
        private final String endpointUrl;
        private final String bearerToken;
        private String sessionId;
        private JsonNode serverInfo;

        public Connection(String endpointUrl, String bearerToken) {
            this.endpointUrl = endpointUrl;
            this.bearerToken = bearerToken;
        }

        public String sessionId() {
            return sessionId;
        }

        public JsonNode serverInfo() {
            return serverInfo;
        }
    }

    /** Opens a reusable HTTP-backed {@link McpSession} (handshakes once). */
    public McpSession newSession(String endpointUrl, String bearerToken) throws McpClientException {
        return new HttpMcpSession(openSession(endpointUrl, bearerToken));
    }

    /** {@link McpSession} backed by a single HTTP connection with a negotiated session id. */
    private final class HttpMcpSession implements McpSession {
        private final Connection connection;

        private HttpMcpSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public JsonNode serverInfo() {
            return connection.serverInfo();
        }

        @Override
        public List<DiscoveredTool> listTools() throws McpClientException {
            return listToolsReusing(connection);
        }

        @Override
        public String callTool(String name, Map<String, Object> arguments) throws McpClientException {
            return callToolReusing(connection, name, arguments);
        }

        @Override
        public void close() {
            // Best-effort session termination so well-behaved servers can free resources promptly.
            terminateSession(connection);
        }
    }

    /** A tool as advertised by the upstream server's {@code tools/list}. */
    public record DiscoveredTool(String name, String description, JsonNode inputSchema) {
    }

    /** Thrown for any transport/protocol failure; {@code getMessage()} is safe to show the model. */
    public static class McpClientException extends Exception {
        public McpClientException(String message) {
            super(message);
        }

        public McpClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Signals that the server no longer recognizes our {@code Mcp-Session-Id} (HTTP 404). The caller
     * should discard the cached session and re-open before retrying.
     */
    public static class McpSessionExpiredException extends McpClientException {
        public McpSessionExpiredException(String message) {
            super(message);
        }
    }

    /**
     * Opens a reusable session: runs the {@code initialize} handshake once and returns the
     * {@link Connection} carrying the negotiated session id. Reuse it with {@link #listToolsReusing} /
     * {@link #callToolReusing} to avoid re-handshaking on every call.
     */
    public Connection openSession(String endpointUrl, String bearerToken) throws McpClientException {
        Connection connection = new Connection(endpointUrl, bearerToken);
        initialize(connection);
        return connection;
    }

    /** {@link #listTools} on an already-open session (no re-initialize). */
    public List<DiscoveredTool> listToolsReusing(Connection connection) throws McpClientException {
        return parseTools(rpc(connection, "tools/list", objectMapper.createObjectNode()));
    }

    /** {@link #callTool} on an already-open session (no re-initialize). */
    public String callToolReusing(Connection connection, String toolName, Map<String, Object> arguments)
            throws McpClientException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = rpc(connection, "tools/call", params);
        return renderToolResult(result);
    }

    /**
     * Performs the MCP {@code initialize} handshake, returning the server's reported info/capabilities.
     * Establishes the session id on {@code connection} for reuse by a follow-up call.
     */
    public JsonNode initialize(Connection connection) throws McpClientException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "hugin");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);

        JsonNode result = rpc(connection, "initialize", params);
        connection.serverInfo = result;
        // Best-effort "initialized" notification; servers that don't require it simply ignore it.
        sendInitializedNotification(connection);
        return result;
    }

    /**
     * Best-effort MCP session termination: sends an HTTP DELETE with the {@code Mcp-Session-Id} so the
     * server can release the session immediately. Never throws — a server that doesn't support it (405)
     * or is unreachable will expire the session on its own.
     */
    private void terminateSession(Connection connection) {
        if (connection.sessionId == null || connection.sessionId.isBlank()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(connection.endpointUrl))
                    .timeout(requestTimeout)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .header("Mcp-Session-Id", connection.sessionId)
                    .DELETE();
            if (connection.bearerToken != null && !connection.bearerToken.isBlank()) {
                builder.header("Authorization", "Bearer " + connection.bearerToken);
            }
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.debug("MCP session terminate failed (ignored): {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Lists tools via {@code tools/list} (handshakes first). */
    public List<DiscoveredTool> listTools(Connection connection) throws McpClientException {
        initialize(connection);
        return parseTools(rpc(connection, "tools/list", objectMapper.createObjectNode()));
    }

    private List<DiscoveredTool> parseTools(JsonNode result) throws McpClientException {
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            throw new McpClientException("Server returned a malformed tools/list response (no 'tools' array).");
        }
        List<DiscoveredTool> discovered = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            String description = tool.path("description").asText("");
            JsonNode schema = tool.has("inputSchema") ? tool.get("inputSchema") : null;
            discovered.add(new DiscoveredTool(name, description, schema));
        }
        return discovered;
    }

    /**
     * Invokes {@code tools/call} (handshakes first) and returns the textual content of the result.
     * MCP {@code content} blocks of type {@code text} are concatenated; an {@code isError: true} result
     * is surfaced as a readable error rather than thrown, so the model can react to it.
     */
    public String callTool(Connection connection, String toolName, Map<String, Object> arguments)
            throws McpClientException {
        initialize(connection);
        return callToolReusing(connection, toolName, arguments);
    }

    private String renderToolResult(JsonNode result) {
        String text = McpResults.extractTextContent(result);
        boolean isError = result.path("isError").asBoolean(false);
        if (isError) {
            return "The MCP tool reported an error: " + (text.isBlank() ? "(no detail provided)" : text);
        }
        return text;
    }

    /** Sends the JSON-RPC request and returns its {@code result} node, mapping errors to exceptions. */
    private JsonNode rpc(Connection connection, String method, JsonNode params) throws McpClientException {
        long id = requestIds.getAndIncrement();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.set("params", params);

        HttpResponse<InputStream> response = post(connection, envelope, false);
        captureSessionId(connection, response);

        int status = response.statusCode();
        String body = readBoundedBody(response);
        if (status == 404 && connection.sessionId != null) {
            // The server has forgotten our session; let the caller re-open and retry.
            throw new McpSessionExpiredException("MCP session expired (HTTP 404) for " + method + ".");
        }
        if (status < 200 || status >= 300) {
            throw new McpClientException("MCP server returned HTTP " + status + " for " + method
                    + (body.isBlank() ? "." : ": " + truncate(body, 300)));
        }

        JsonNode message = parseJsonRpc(body, response);
        if (message == null) {
            throw new McpClientException("MCP server returned no usable JSON-RPC response for " + method + ".");
        }
        if (message.has("error") && !message.get("error").isNull()) {
            JsonNode error = message.get("error");
            String errMessage = error.path("message").asText("unknown error");
            int code = error.path("code").asInt(0);
            throw new McpClientException("MCP server error (" + code + "): " + errMessage);
        }
        JsonNode result = message.path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new McpClientException("MCP server response for " + method + " had no result.");
        }
        return result;
    }

    /** Fire-and-forget {@code notifications/initialized}; failures are logged but never fatal. */
    private void sendInitializedNotification(Connection connection) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", "notifications/initialized");
            envelope.set("params", objectMapper.createObjectNode());
            post(connection, envelope, true);
        } catch (McpClientException e) {
            log.debug("MCP initialized notification was not accepted (continuing): {}", e.getMessage());
        }
    }

    private HttpResponse<InputStream> post(Connection connection, JsonNode envelope, boolean notification)
            throws McpClientException {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (IOException e) {
            throw new McpClientException("Could not serialize MCP request.", e);
        }

        URI uri;
        try {
            uri = URI.create(connection.endpointUrl);
        } catch (RuntimeException e) {
            throw new McpClientException("Invalid MCP endpoint URL: " + connection.endpointUrl);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (connection.bearerToken != null && !connection.bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + connection.bearerToken);
        }
        if (connection.sessionId != null && !connection.sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", connection.sessionId);
        }

        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new McpClientException("Could not reach MCP server at " + connection.endpointUrl
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP request was interrupted.", e);
        }
    }

    private void captureSessionId(Connection connection, HttpResponse<InputStream> response) {
        response.headers().firstValue("Mcp-Session-Id")
                .filter(value -> !value.isBlank())
                .ifPresent(value -> connection.sessionId = value);
    }

    /** Reads at most {@link #maxResponseBytes} of the body, then closes the stream. */
    private String readBoundedBody(HttpResponse<InputStream> response) throws McpClientException {
        try (InputStream in = response.body()) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new McpClientException("MCP server response exceeded the maximum allowed size ("
                            + maxResponseBytes + " bytes).");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McpClientException("Failed reading MCP server response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a JSON-RPC response from either a plain JSON body or an SSE ({@code text/event-stream})
     * body. For SSE, the {@code data:} payloads are scanned and the first frame that parses as a
     * JSON-RPC response object (has {@code result} or {@code error}) is returned.
     */
    private JsonNode parseJsonRpc(String body, HttpResponse<InputStream> response) throws McpClientException {
        if (body == null || body.isBlank()) {
            return null;
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        try {
            if (contentType.contains("text/event-stream") || body.stripLeading().startsWith("event:")
                    || body.stripLeading().startsWith("data:")) {
                return parseSse(body);
            }
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new McpClientException("MCP server returned invalid JSON: " + e.getMessage());
        }
    }

    private JsonNode parseSse(String body) throws IOException {
        JsonNode firstParsable = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (trimmed.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append("\n");
                }
                data.append(trimmed.substring(5).stripLeading());
            } else if (trimmed.isEmpty() && data.length() > 0) {
                JsonNode candidate = tryParse(data.toString());
                data.setLength(0);
                if (candidate != null && (candidate.has("result") || candidate.has("error"))) {
                    return candidate;
                }
                if (candidate != null && firstParsable == null) {
                    firstParsable = candidate;
                }
            }
        }
        if (data.length() > 0) {
            JsonNode candidate = tryParse(data.toString());
            if (candidate != null && (candidate.has("result") || candidate.has("error"))) {
                return candidate;
            }
            if (candidate != null && firstParsable == null) {
                firstParsable = candidate;
            }
        }
        return firstParsable;
    }

    private JsonNode tryParse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
