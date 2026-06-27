package com.example.integration.mcp;

import java.util.List;
import java.util.Map;

/**
 * Request body for creating an MCP server. The owner is ALWAYS taken from the authenticated session,
 * never from this body.
 *
 * <p>Fields used depend on transport/auth:
 * <ul>
 *   <li>{@code STREAMABLE_HTTP} → {@code endpointUrl} (required).</li>
 *   <li>{@code STDIO} → {@code command} (required), optional {@code args}/{@code env}.</li>
 *   <li>{@code BEARER_TOKEN} auth → {@code bearerToken}.</li>
 *   <li>{@code OAUTH} auth → optionally {@code oauthScope} and explicit endpoints/client; anything
 *       omitted is discovered (RFC 8414/9728) or registered dynamically (RFC 7591) at connect time.</li>
 * </ul>
 */
public record McpCreateRequest(
        String name,
        String displayName,
        String transport,
        String endpointUrl,
        String authType,
        String bearerToken,
        // stdio transport
        String command,
        List<String> args,
        Map<String, String> env,
        // OAuth (all optional; discovered/registered when absent)
        String oauthScope,
        String oauthAuthorizationEndpoint,
        String oauthTokenEndpoint,
        String oauthRegistrationEndpoint,
        String oauthClientId,
        String oauthClientSecret) {

    /** Convenience constructor for the common HTTP case (no stdio/OAuth extras). */
    public McpCreateRequest(String name, String displayName, String transport, String endpointUrl,
                            String authType, String bearerToken) {
        this(name, displayName, transport, endpointUrl, authType, bearerToken,
                null, null, null, null, null, null, null, null, null);
    }
}
