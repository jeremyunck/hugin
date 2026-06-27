package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.integration.mcp.McpHttpClient.McpClientException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Thin HTTP helper for the OAuth flows (metadata discovery, dynamic client registration, token
 * endpoint). Separated from {@link McpOAuthService} so the protocol logic can be unit-tested with a
 * mocked transport. Returns parsed JSON; non-2xx responses raise {@link McpClientException} with a
 * concise message (never a stack trace, never the request secrets).
 */
@Component
public class McpOAuthHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public McpOAuthHttpClient(ObjectMapper objectMapper,
                              @Value("${mcp.oauth.request-timeout-seconds:20}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** GETs JSON, returning empty when the resource is absent (404) rather than throwing. */
    public Optional<JsonNode> getJsonOptional(String url) throws McpClientException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new McpClientException("OAuth metadata request to " + url + " returned HTTP "
                    + response.statusCode());
        }
        return Optional.of(parse(response.body()));
    }

    /** POSTs an {@code application/x-www-form-urlencoded} body (e.g. token endpoint) and parses JSON. */
    public JsonNode postForm(String url, Map<String, String> form, String authorizationHeader)
            throws McpClientException {
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(encode(k) + "=" + encode(v)));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri(url))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            builder.header("Authorization", authorizationHeader);
        }
        HttpResponse<String> response = send(builder.build());
        if (response.statusCode() / 100 != 2) {
            throw new McpClientException("OAuth token endpoint returned HTTP " + response.statusCode()
                    + ": " + summarizeError(response.body()));
        }
        return parse(response.body());
    }

    /** POSTs a JSON body (e.g. dynamic client registration) and parses the JSON response. */
    public JsonNode postJson(String url, Object body) throws McpClientException {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new McpClientException("Could not serialize OAuth request body.", e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            throw new McpClientException("OAuth client registration returned HTTP " + response.statusCode()
                    + ": " + summarizeError(response.body()));
        }
        return parse(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws McpClientException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new McpClientException("OAuth request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("OAuth request was interrupted.", e);
        }
    }

    private JsonNode parse(String body) throws McpClientException {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new McpClientException("OAuth endpoint returned invalid JSON: " + e.getMessage());
        }
    }

    private static String summarizeError(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private static URI uri(String url) throws McpClientException {
        try {
            return URI.create(url);
        } catch (RuntimeException e) {
            throw new McpClientException("Invalid OAuth URL: " + url);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
