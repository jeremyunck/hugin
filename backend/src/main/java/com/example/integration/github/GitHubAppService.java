package com.example.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Authenticates Hugin to the GitHub REST API as a <b>GitHub App</b> and reports connection status.
 *
 * <p>Two token layers are involved:
 * <ul>
 *   <li>An <b>App JWT</b>, signed locally (RS256) with the App's private key. It is short-lived
 *       (max 10 minutes) and only used to talk to the {@code /app/**} endpoints.</li>
 *   <li>An <b>installation access token</b>, obtained by POSTing to
 *       {@code /app/installations/{id}/access_tokens} with the App JWT. This is what the
 *       {@code github_*} tools use to act on a specific installation; it is cached until shortly
 *       before its one-hour expiry.</li>
 * </ul>
 *
 * <p>All failures are non-fatal: when the App is unconfigured or unreachable the integration simply
 * reports itself inactive so the rest of Hugin keeps working.
 */
@Service
public class GitHubAppService {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppService.class);
    private static final Duration STATUS_CACHE = Duration.ofSeconds(30);
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(60);
    private static final String API_VERSION = "2022-11-28";

    private final GitHubProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private PrivateKey cachedKey;

    // Resolved installation (id + account login), cached after first lookup.
    private Long resolvedInstallationId;
    private String installationAccount;

    // Cached installation access token.
    private String installationToken;
    private Instant installationTokenExpiry = Instant.EPOCH;

    // Cached status snapshot, to keep the integrations endpoint cheap.
    private GitHubStatus cachedStatus;
    private Instant cachedStatusAt = Instant.EPOCH;

    public GitHubAppService(GitHubProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The public install page for the App's "Connect" button, or empty when no slug is configured. */
    public Optional<String> installUrl(String returnTo) {
        if (properties.appSlug().isBlank()) {
            return Optional.empty();
        }
        StringBuilder url = new StringBuilder(properties.webBaseUrl())
                .append("/apps/")
                .append(properties.appSlug())
                .append("/installations/new");
        if (returnTo != null && !returnTo.isBlank()) {
            url.append("?state=").append(URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
        }
        return Optional.of(url.toString());
    }

    /** Begins a connect flow: returns the current status plus the install URL the UI should open. */
    public synchronized GitHubConnectResponse beginConnect(String returnTo) {
        // Force a fresh status read so the UI reflects reality right after install.
        invalidate();
        return new GitHubConnectResponse(status(), installUrl(returnTo).orElse(null));
    }

    /** Forgets the cached installation and tokens so the next call re-resolves from scratch. */
    public synchronized GitHubStatus disconnect() {
        invalidate();
        resolvedInstallationId = null;
        installationAccount = null;
        return status();
    }

    /** Forces the next status read to re-check GitHub after an install/setup redirect. */
    public synchronized void refresh() {
        invalidate();
        resolvedInstallationId = null;
        installationAccount = null;
    }

    /** Current connection snapshot, cached briefly to keep {@code GET /api/integrations} cheap. */
    public synchronized GitHubStatus status() {
        if (cachedStatus != null && Duration.between(cachedStatusAt, Instant.now()).compareTo(STATUS_CACHE) < 0) {
            return cachedStatus;
        }

        GitHubStatus status = computeStatus();
        cachedStatus = status;
        cachedStatusAt = Instant.now();
        return status;
    }

    private GitHubStatus computeStatus() {
        // The in-UI Connect button is only useful when we can build an install URL (App slug set).
        boolean canInstall = installUrl(null).isPresent();
        if (!properties.configured()) {
            return new GitHubStatus(false, false, canInstall, "github-app", "",
                    "GitHub App is not configured. Set github.app-id and github.private-key (or "
                            + "github.private-key-path) to enable it.");
        }
        try {
            String token = currentInstallationToken();
            if (token == null) {
                return new GitHubStatus(false, true, canInstall, "github-app", "",
                        "GitHub App is configured but not installed yet. Click Connect to install it "
                                + "on your account or organization.");
            }
            String account = installationAccount == null ? "" : installationAccount;
            String where = account.isBlank() ? "" : " (" + account + ")";
            return new GitHubStatus(true, true, true, "github-app", account,
                    "Connected to GitHub as a GitHub App" + where + ".");
        } catch (Exception e) {
            log.warn("GitHub App status check failed: {}", e.getMessage());
            return new GitHubStatus(false, true, canInstall, "github-app", "",
                    "GitHub App is configured but the connection failed: " + e.getMessage());
        }
    }

    /** A valid installation access token for the {@code github_*} tools, or empty when not connected. */
    public synchronized Optional<String> installationToken() {
        if (!properties.configured()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(currentInstallationToken());
        } catch (Exception e) {
            log.warn("Could not obtain GitHub installation token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether the {@code github_*} tools should be advertised right now. */
    public boolean isActive() {
        return status().active();
    }

    /**
     * Calls the GitHub REST API with the installation token. {@code path} is API-root-relative
     * (e.g. {@code /installation/repositories}); {@code jsonBody} may be {@code null} for GET.
     */
    public synchronized HttpResponse<String> api(String method, String path, String jsonBody) throws Exception {
        String token = currentInstallationToken();
        if (token == null) {
            throw new IllegalStateException("GitHub App is not installed; no installation token available.");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", API_VERSION);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // --- token machinery -------------------------------------------------------------------------

    /** Returns a live installation token (minting/refreshing as needed), or null if not installed. */
    private String currentInstallationToken() throws Exception {
        if (installationToken != null
                && Instant.now().isBefore(installationTokenExpiry.minus(TOKEN_REFRESH_SKEW))) {
            return installationToken;
        }
        Long installationId = resolveInstallationId();
        if (installationId == null) {
            return null;
        }

        String jwt = buildAppJwt();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBaseUrl() + "/app/installations/" + installationId + "/access_tokens"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + jwt)
                .header("X-GitHub-Api-Version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("token exchange failed: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        installationToken = root.path("token").asText("");
        String expiresAt = root.path("expires_at").asText("");
        installationTokenExpiry = expiresAt.isBlank() ? Instant.now().plus(Duration.ofMinutes(55))
                : Instant.parse(expiresAt);
        return installationToken.isBlank() ? null : installationToken;
    }

    /** Resolves the installation id to use: the pinned one, or the App's first installation. */
    private Long resolveInstallationId() throws Exception {
        if (resolvedInstallationId != null) {
            return resolvedInstallationId;
        }
        if (properties.installationId() != null && properties.installationId() > 0) {
            resolvedInstallationId = properties.installationId();
            installationAccount = lookupAccount(resolvedInstallationId);
            return resolvedInstallationId;
        }

        String jwt = buildAppJwt();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBaseUrl() + "/app/installations?per_page=1"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + jwt)
                .header("X-GitHub-Api-Version", API_VERSION)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("listing installations failed: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        JsonNode installations = objectMapper.readTree(response.body());
        if (!installations.isArray() || installations.isEmpty()) {
            return null; // App configured but not installed anywhere yet.
        }
        JsonNode first = installations.get(0);
        resolvedInstallationId = first.path("id").asLong();
        installationAccount = first.path("account").path("login").asText("");
        return resolvedInstallationId;
    }

    private String lookupAccount(long installationId) throws Exception {
        String jwt = buildAppJwt();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBaseUrl() + "/app/installations/" + installationId))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + jwt)
                .header("X-GitHub-Api-Version", API_VERSION)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            return "";
        }
        return objectMapper.readTree(response.body()).path("account").path("login").asText("");
    }

    /** Builds and signs a short-lived (10 min) RS256 App JWT, with {@code iss} = the App ID. */
    private String buildAppJwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        // Backdate iat by 60s to tolerate minor clock skew against GitHub.
        String payloadJson = "{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540)
                + ",\"iss\":\"" + properties.appId() + "\"}";
        String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signature = base64Url(signer.sign());
        return signingInput + "." + signature;
    }

    private PrivateKey privateKey() throws Exception {
        if (cachedKey != null) {
            return cachedKey;
        }
        String pem = properties.privateKey();
        if (pem.isBlank() && !properties.privateKeyPath().isBlank()) {
            pem = Files.readString(expandHome(properties.privateKeyPath()));
        }
        cachedKey = RsaPrivateKeys.parse(pem);
        return cachedKey;
    }

    private static Path expandHome(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return Path.of(System.getProperty("user.home"), path.length() > 1 ? path.substring(2) : "");
        }
        return Path.of(path);
    }

    private void invalidate() {
        cachedStatus = null;
        cachedStatusAt = Instant.EPOCH;
        installationToken = null;
        installationTokenExpiry = Instant.EPOCH;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
