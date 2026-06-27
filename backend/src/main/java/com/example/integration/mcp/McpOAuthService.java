package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.integration.mcp.McpHttpClient.McpClientException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implements OAuth 2.1 (Authorization Code + PKCE, with Dynamic Client Registration) for MCP servers.
 *
 * <p>Flow: {@link #startAuthorization} discovers the authorization server (RFC 8414 / RFC 9728 well-known
 * metadata) unless endpoints were supplied, registers a client via DCR (RFC 7591) if needed, then builds
 * a PKCE authorization URL and records the request in {@link McpOAuthStateRepository}. The browser
 * returns to {@link #handleCallback}, which exchanges the code for tokens at the token endpoint. Tokens
 * are encrypted at rest in the server's {@link McpServerConfig}; {@link #currentAccessToken} returns a
 * valid access token, refreshing transparently when it is expired or about to expire.
 *
 * <p>All secrets (client secret, access/refresh tokens) are encrypted before storage and never logged
 * or returned to clients.
 */
@Service
public class McpOAuthService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    /** Refresh an access token this long before it actually expires. */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    private final McpServerRepository serverRepository;
    private final McpOAuthStateRepository stateRepository;
    private final McpSecretEncryptionService encryption;
    private final McpOAuthHttpClient http;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public McpOAuthService(McpServerRepository serverRepository,
                           McpOAuthStateRepository stateRepository,
                           McpSecretEncryptionService encryption,
                           McpOAuthHttpClient http,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.serverRepository = serverRepository;
        this.stateRepository = stateRepository;
        this.encryption = encryption;
        this.http = http;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Outcome of the OAuth callback, used to render a small confirmation page. */
    public record CallbackResult(boolean success, String message) {
    }

    /**
     * Begins the Authorization Code + PKCE flow for an OAUTH server and returns the authorization URL
     * the user's browser should visit. {@code redirectUri} is this app's callback endpoint.
     */
    public String startAuthorization(McpServerEntity server, String redirectUri) {
        if (server.authType() != McpAuthType.OAUTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Server does not use OAuth authentication");
        }
        McpServerConfig config = McpServerConfig.parse(objectMapper, server.configJson());
        McpServerConfig.OAuth oauth = config.oauth() == null
                ? new McpServerConfig.OAuth(null, null, null, null, null, null, server.endpointUrl(), null, null, null)
                : config.oauth();
        try {
            oauth = ensureEndpoints(server, oauth);
            oauth = ensureClient(oauth, redirectUri);
        } catch (McpClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not set up OAuth for this server: " + e.getMessage());
        }
        // Persist discovered endpoints / registered client before redirecting so the callback can use them.
        persist(server, config.withOAuth(oauth));

        String codeVerifier = randomUrlSafe(48);
        String codeChallenge = s256Challenge(codeVerifier);
        String state = randomUrlSafe(24);
        stateRepository.insert(new McpOAuthStateRepository.State(
                state, server.id(), server.ownerUsername(), codeVerifier, redirectUri, Instant.now(clock)));

        return buildAuthorizationUrl(oauth, redirectUri, state, codeChallenge, server.endpointUrl());
    }

    /** Handles the OAuth redirect: exchanges the authorization code for tokens and stores them. */
    public CallbackResult handleCallback(String state, String code, String error) {
        stateRepository.deleteOlderThan(Instant.now(clock).minus(STATE_TTL));
        if (error != null && !error.isBlank()) {
            return new CallbackResult(false, "Authorization was denied: " + error);
        }
        if (state == null || code == null || state.isBlank() || code.isBlank()) {
            return new CallbackResult(false, "Missing authorization code or state.");
        }
        Optional<McpOAuthStateRepository.State> stored = stateRepository.find(state);
        if (stored.isEmpty()) {
            return new CallbackResult(false, "Unknown or expired authorization request.");
        }
        McpOAuthStateRepository.State pending = stored.get();
        stateRepository.delete(state);
        if (pending.createdAt() != null && pending.createdAt().isBefore(Instant.now(clock).minus(STATE_TTL))) {
            return new CallbackResult(false, "Authorization request expired. Please try again.");
        }

        // The server row is loaded without an owner filter here because the callback is a public
        // endpoint with no session; ownership is pinned by the state row's owner_username instead.
        Optional<McpServerEntity> serverOpt =
                serverRepository.findByIdAndOwner(pending.serverId(), pending.ownerUsername());
        if (serverOpt.isEmpty()) {
            return new CallbackResult(false, "The MCP server no longer exists.");
        }
        McpServerEntity server = serverOpt.get();
        McpServerConfig config = McpServerConfig.parse(objectMapper, server.configJson());
        McpServerConfig.OAuth oauth = config.oauth();
        if (oauth == null || oauth.tokenEndpoint() == null) {
            return new CallbackResult(false, "OAuth is not configured for this server.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", pending.redirectUri());
        form.put("code_verifier", pending.codeVerifier());
        if (oauth.clientId() != null) {
            form.put("client_id", oauth.clientId());
        }
        if (server.endpointUrl() != null) {
            form.put("resource", server.endpointUrl());
        }
        addClientSecret(form, oauth);

        try {
            JsonNode tokens = http.postForm(oauth.tokenEndpoint(), form, null);
            persistTokens(server, config, oauth, tokens, oauth.refreshTokenEncrypted());
            return new CallbackResult(true, "Connected. You can close this window.");
        } catch (McpClientException e) {
            log.info("OAuth token exchange failed for server {}: {}", server.id(), e.getMessage());
            return new CallbackResult(false, "Token exchange failed: " + e.getMessage());
        }
    }

    /**
     * Returns a currently-valid access token for the server, refreshing it when expired/near-expiry.
     * Returns {@code null} when the server has not completed OAuth yet (caller treats it as "needs auth").
     */
    public String currentAccessToken(McpServerEntity server) {
        McpServerConfig config = McpServerConfig.parse(objectMapper, server.configJson());
        McpServerConfig.OAuth oauth = config.oauth();
        if (oauth == null || !oauth.hasTokens()) {
            return null;
        }
        boolean expired = oauth.accessTokenExpiresAtEpochMs() != null
                && Instant.ofEpochMilli(oauth.accessTokenExpiresAtEpochMs())
                        .isBefore(Instant.now(clock).plus(REFRESH_SKEW));
        if (!expired) {
            return safeDecrypt(oauth.accessTokenEncrypted());
        }
        if (oauth.refreshTokenEncrypted() == null) {
            return safeDecrypt(oauth.accessTokenEncrypted()); // no refresh token; try the (possibly stale) token
        }
        try {
            return refresh(server, config, oauth);
        } catch (McpClientException e) {
            log.info("OAuth refresh failed for server {}: {}", server.id(), e.getMessage());
            return null;
        }
    }

    private String refresh(McpServerEntity server, McpServerConfig config, McpServerConfig.OAuth oauth)
            throws McpClientException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", safeDecrypt(oauth.refreshTokenEncrypted()));
        if (oauth.clientId() != null) {
            form.put("client_id", oauth.clientId());
        }
        if (oauth.scope() != null) {
            form.put("scope", oauth.scope());
        }
        if (server.endpointUrl() != null) {
            form.put("resource", server.endpointUrl());
        }
        addClientSecret(form, oauth);

        JsonNode tokens = http.postForm(oauth.tokenEndpoint(), form, null);
        persistTokens(server, config, oauth, tokens, oauth.refreshTokenEncrypted());
        String access = tokens.path("access_token").asText(null);
        return access;
    }

    /* ------------------------------ discovery / registration ------------------------------ */

    private McpServerConfig.OAuth ensureEndpoints(McpServerEntity server, McpServerConfig.OAuth oauth)
            throws McpClientException {
        if (oauth.authorizationEndpoint() != null && oauth.tokenEndpoint() != null) {
            return oauth; // explicitly configured or previously discovered
        }
        JsonNode metadata = discoverMetadata(server.endpointUrl());
        if (metadata == null) {
            throw new McpClientException("Could not discover the server's OAuth endpoints. "
                    + "Provide authorizationEndpoint and tokenEndpoint explicitly.");
        }
        String authEndpoint = firstNonBlank(oauth.authorizationEndpoint(),
                metadata.path("authorization_endpoint").asText(null));
        String tokenEndpoint = firstNonBlank(oauth.tokenEndpoint(),
                metadata.path("token_endpoint").asText(null));
        String registration = firstNonBlank(oauth.registrationEndpoint(),
                metadata.path("registration_endpoint").asText(null));
        if (authEndpoint == null || tokenEndpoint == null) {
            throw new McpClientException("Authorization server metadata is missing required endpoints.");
        }
        return new McpServerConfig.OAuth(oauth.clientId(), oauth.clientSecretEncrypted(), authEndpoint,
                tokenEndpoint, registration, oauth.scope(), oauth.resource(), oauth.accessTokenEncrypted(),
                oauth.refreshTokenEncrypted(), oauth.accessTokenExpiresAtEpochMs());
    }

    /** Tries RFC 9728 protected-resource metadata, then RFC 8414 / OIDC well-known on the origin. */
    private JsonNode discoverMetadata(String endpointUrl) throws McpClientException {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return null;
        }
        String origin = origin(endpointUrl);
        if (origin == null) {
            return null;
        }
        // RFC 9728: the protected resource advertises its authorization server(s).
        Optional<JsonNode> resourceMeta = http.getJsonOptional(origin + "/.well-known/oauth-protected-resource");
        if (resourceMeta.isPresent()) {
            JsonNode servers = resourceMeta.get().path("authorization_servers");
            if (servers.isArray() && !servers.isEmpty()) {
                String as = servers.get(0).asText(null);
                if (as != null) {
                    JsonNode asMeta = fetchAuthServerMetadata(as);
                    if (asMeta != null) {
                        return asMeta;
                    }
                }
            }
        }
        // Fall back to treating the resource origin as the authorization server.
        return fetchAuthServerMetadata(origin);
    }

    private JsonNode fetchAuthServerMetadata(String authServerUrl) throws McpClientException {
        String base = authServerUrl.endsWith("/")
                ? authServerUrl.substring(0, authServerUrl.length() - 1) : authServerUrl;
        Optional<JsonNode> oauthMeta = http.getJsonOptional(base + "/.well-known/oauth-authorization-server");
        if (oauthMeta.isPresent()) {
            return oauthMeta.get();
        }
        return http.getJsonOptional(base + "/.well-known/openid-configuration").orElse(null);
    }

    private McpServerConfig.OAuth ensureClient(McpServerConfig.OAuth oauth, String redirectUri)
            throws McpClientException {
        if (oauth.clientId() != null && !oauth.clientId().isBlank()) {
            return oauth; // already registered or provided
        }
        if (oauth.registrationEndpoint() == null || oauth.registrationEndpoint().isBlank()) {
            throw new McpClientException("No client_id configured and the server does not support dynamic "
                    + "client registration. Provide a clientId (and clientSecret) explicitly.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", "Hugin");
        body.put("redirect_uris", java.util.List.of(redirectUri));
        body.put("grant_types", java.util.List.of("authorization_code", "refresh_token"));
        body.put("response_types", java.util.List.of("code"));
        body.put("token_endpoint_auth_method", "none"); // public client + PKCE
        JsonNode registered = http.postJson(oauth.registrationEndpoint(), body);
        String clientId = registered.path("client_id").asText(null);
        if (clientId == null || clientId.isBlank()) {
            throw new McpClientException("Dynamic client registration did not return a client_id.");
        }
        String clientSecret = registered.path("client_secret").asText(null);
        String clientSecretEncrypted = (clientSecret == null || clientSecret.isBlank())
                ? null : encryption.encrypt(clientSecret);
        return new McpServerConfig.OAuth(clientId, clientSecretEncrypted, oauth.authorizationEndpoint(),
                oauth.tokenEndpoint(), oauth.registrationEndpoint(), oauth.scope(), oauth.resource(),
                oauth.accessTokenEncrypted(), oauth.refreshTokenEncrypted(), oauth.accessTokenExpiresAtEpochMs());
    }

    /* ----------------------------------- helpers ----------------------------------- */

    private String buildAuthorizationUrl(McpServerConfig.OAuth oauth, String redirectUri, String state,
                                         String codeChallenge, String resource) {
        StringBuilder url = new StringBuilder(oauth.authorizationEndpoint());
        url.append(oauth.authorizationEndpoint().contains("?") ? "&" : "?");
        url.append("response_type=code");
        url.append("&client_id=").append(urlEncode(oauth.clientId()));
        url.append("&redirect_uri=").append(urlEncode(redirectUri));
        url.append("&state=").append(urlEncode(state));
        url.append("&code_challenge=").append(urlEncode(codeChallenge));
        url.append("&code_challenge_method=S256");
        if (oauth.scope() != null && !oauth.scope().isBlank()) {
            url.append("&scope=").append(urlEncode(oauth.scope()));
        }
        if (resource != null && !resource.isBlank()) {
            url.append("&resource=").append(urlEncode(resource));
        }
        return url.toString();
    }

    private void addClientSecret(Map<String, String> form, McpServerConfig.OAuth oauth) {
        if (oauth.clientSecretEncrypted() != null && !oauth.clientSecretEncrypted().isBlank()) {
            form.put("client_secret", safeDecrypt(oauth.clientSecretEncrypted()));
        }
    }

    private void persistTokens(McpServerEntity server, McpServerConfig config, McpServerConfig.OAuth oauth,
                               JsonNode tokens, String existingRefreshEncrypted) {
        String accessToken = tokens.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Token response had no access_token");
        }
        String accessEnc = encryption.encrypt(accessToken);
        String refreshToken = tokens.path("refresh_token").asText(null);
        String refreshEnc = (refreshToken == null || refreshToken.isBlank())
                ? existingRefreshEncrypted : encryption.encrypt(refreshToken);
        Long expiresAt = null;
        long expiresIn = tokens.path("expires_in").asLong(0);
        if (expiresIn > 0) {
            expiresAt = Instant.now(clock).plusSeconds(expiresIn).toEpochMilli();
        }
        McpServerConfig.OAuth updated = oauth.withTokens(accessEnc, refreshEnc, expiresAt);
        persist(server, config.withOAuth(updated));
    }

    private void persist(McpServerEntity server, McpServerConfig config) {
        serverRepository.updateConfigJson(server.id(), config.toJson(objectMapper));
    }

    private String safeDecrypt(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            return encryption.decrypt(encrypted);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String origin(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            StringBuilder origin = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                origin.append(":").append(uri.getPort());
            }
            return origin.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String s256Challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }
}
