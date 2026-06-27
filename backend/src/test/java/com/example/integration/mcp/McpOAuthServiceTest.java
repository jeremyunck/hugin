package com.example.integration.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Tests the OAuth flow: discovery + DCR, authorization URL, code exchange, and token refresh. */
class McpOAuthServiceTest extends AbstractMcpDbTest {

    private McpOAuthStateRepository stateRepository;
    private McpOAuthHttpClient http;
    private McpOAuthService oauth;

    @BeforeEach
    void setUpOAuth() {
        stateRepository = new McpOAuthStateRepository(jdbcTemplate);
        http = Mockito.mock(McpOAuthHttpClient.class);
        oauth = new McpOAuthService(serverRepository, stateRepository, encryption, http, objectMapper, clock);
    }

    private McpServerEntity oauthServer(McpServerConfig.OAuth oauthConfig) {
        Instant now = Instant.now(clock);
        McpServerConfig config = new McpServerConfig(null, oauthConfig);
        McpServerEntity server = new McpServerEntity(UUID.randomUUID().toString(), "alice", "linear", "Linear",
                McpTransport.STREAMABLE_HTTP, "https://mcp.example.com/mcp", McpAuthType.OAUTH, null, true,
                now, now, config.toJson(objectMapper));
        serverRepository.insert(server);
        return server;
    }

    @Test
    void startWithExplicitEndpointsBuildsPkceUrlAndStoresState() {
        insertUser("alice");
        McpServerEntity server = oauthServer(new McpServerConfig.OAuth(
                "client-123", null, "https://auth.example.com/authorize", "https://auth.example.com/token",
                null, "read", "https://mcp.example.com/mcp", null, null, null));

        String url = oauth.startAuthorization(server, "https://bouw.app/api/mcp/oauth/callback");

        assertThat(url).startsWith("https://auth.example.com/authorize?");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=client-123");
        assertThat(url).contains("code_challenge=");
        assertThat(url).contains("code_challenge_method=S256");
        assertThat(url).contains("scope=read");
        // A pending authorization request was recorded.
        Integer states = jdbcTemplate.queryForObject("select count(*) from mcp_oauth_states", Integer.class);
        assertThat(states).isEqualTo(1);
    }

    @Test
    void startDiscoversMetadataAndRegistersClientDynamically() throws Exception {
        insertUser("alice");
        McpServerEntity server = oauthServer(new McpServerConfig.OAuth(
                null, null, null, null, null, null, "https://mcp.example.com/mcp", null, null, null));

        when(http.getJsonOptional(contains("oauth-protected-resource"))).thenReturn(Optional.empty());
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("authorization_endpoint", "https://as.example.com/authorize");
        metadata.put("token_endpoint", "https://as.example.com/token");
        metadata.put("registration_endpoint", "https://as.example.com/register");
        when(http.getJsonOptional(contains("oauth-authorization-server"))).thenReturn(Optional.of(metadata));
        ObjectNode registered = objectMapper.createObjectNode();
        registered.put("client_id", "dyn-client");
        when(http.postJson(eq("https://as.example.com/register"), any())).thenReturn(registered);

        String url = oauth.startAuthorization(server, "https://bouw.app/api/mcp/oauth/callback");

        assertThat(url).startsWith("https://as.example.com/authorize?");
        assertThat(url).contains("client_id=dyn-client");
        // Discovered endpoints + registered client persisted for the callback to use.
        McpServerConfig stored = McpServerConfig.parse(objectMapper,
                serverRepository.findByIdAndOwner(server.id(), "alice").orElseThrow().configJson());
        assertThat(stored.oauth().clientId()).isEqualTo("dyn-client");
        assertThat(stored.oauth().tokenEndpoint()).isEqualTo("https://as.example.com/token");
    }

    @Test
    void callbackExchangesCodeAndStoresTokens() throws Exception {
        insertUser("alice");
        McpServerEntity server = oauthServer(new McpServerConfig.OAuth(
                "client-123", null, "https://auth.example.com/authorize", "https://auth.example.com/token",
                null, null, "https://mcp.example.com/mcp", null, null, null));
        stateRepository.insert(new McpOAuthStateRepository.State(
                "state-1", server.id(), "alice", "verifier-1", "https://bouw.app/api/mcp/oauth/callback",
                Instant.now(clock)));

        ObjectNode tokens = objectMapper.createObjectNode();
        tokens.put("access_token", "AT");
        tokens.put("refresh_token", "RT");
        tokens.put("expires_in", 3600);
        when(http.postForm(eq("https://auth.example.com/token"), any(), any())).thenReturn(tokens);

        McpOAuthService.CallbackResult result = oauth.handleCallback("state-1", "the-code", null);

        assertThat(result.success()).isTrue();
        McpServerEntity reloaded = serverRepository.findByIdAndOwner(server.id(), "alice").orElseThrow();
        assertThat(oauth.currentAccessToken(reloaded)).isEqualTo("AT");
        // The state row was consumed.
        assertThat(stateRepository.find("state-1")).isEmpty();
    }

    @Test
    void currentAccessTokenRefreshesWhenExpired() throws Exception {
        insertUser("alice");
        long past = Instant.now(clock).minusSeconds(10).toEpochMilli();
        McpServerEntity server = oauthServer(new McpServerConfig.OAuth(
                "client-123", null, "https://auth.example.com/authorize", "https://auth.example.com/token",
                null, null, "https://mcp.example.com/mcp",
                encryption.encrypt("old-token"), encryption.encrypt("refresh-token"), past));

        ObjectNode tokens = objectMapper.createObjectNode();
        tokens.put("access_token", "new-token");
        tokens.put("expires_in", 3600);
        when(http.postForm(eq("https://auth.example.com/token"), any(), any())).thenReturn(tokens);

        String token = oauth.currentAccessToken(server);

        assertThat(token).isEqualTo("new-token");
    }

    @Test
    void callbackRejectsUnknownState() {
        McpOAuthService.CallbackResult result = oauth.handleCallback("nope", "code", null);
        assertThat(result.success()).isFalse();
    }
}
