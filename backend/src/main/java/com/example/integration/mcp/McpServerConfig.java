package com.example.integration.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Transport- and auth-specific configuration persisted in {@code mcp_servers.config_json}.
 *
 * <p>Kept out of the wide table so new transports/auth schemes have an obvious home. Sensitive values
 * ({@code clientSecretEncrypted}, {@code accessTokenEncrypted}, {@code refreshTokenEncrypted}) are
 * already encrypted before they reach this object, so the blob is safe at rest but must still never be
 * returned to clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(Stdio stdio, OAuth oauth) {

    /** Stdio transport launch spec. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stdio(String command, List<String> args, Map<String, String> env) {
        public Stdio {
            if (args == null) {
                args = List.of();
            }
            if (env == null) {
                env = Map.of();
            }
        }
    }

    /** OAuth 2.1 client registration + endpoints + (encrypted) tokens. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuth(
            String clientId,
            String clientSecretEncrypted,
            String authorizationEndpoint,
            String tokenEndpoint,
            String registrationEndpoint,
            String scope,
            String resource,
            String accessTokenEncrypted,
            String refreshTokenEncrypted,
            Long accessTokenExpiresAtEpochMs) {

        public boolean hasTokens() {
            return accessTokenEncrypted != null && !accessTokenEncrypted.isBlank();
        }

        public OAuth withTokens(String accessTokenEncrypted, String refreshTokenEncrypted,
                                Long expiresAtEpochMs) {
            return new OAuth(clientId, clientSecretEncrypted, authorizationEndpoint, tokenEndpoint,
                    registrationEndpoint, scope, resource, accessTokenEncrypted, refreshTokenEncrypted,
                    expiresAtEpochMs);
        }
    }

    public static McpServerConfig empty() {
        return new McpServerConfig(null, null);
    }

    public static McpServerConfig parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            McpServerConfig parsed = mapper.readValue(json, McpServerConfig.class);
            return parsed == null ? empty() : parsed;
        } catch (Exception e) {
            return empty();
        }
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize MCP server config", e);
        }
    }

    public McpServerConfig withOAuth(OAuth newOAuth) {
        return new McpServerConfig(stdio, newOAuth);
    }
}
