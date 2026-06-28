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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates Bouw to the GitHub REST API as a <b>GitHub App</b> and reports connection status.
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
 * reports itself inactive so the rest of Bouw keeps working.
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

    // Every installation of this App (id + account login), cached briefly. A GitHub App can be
    // installed on the user's personal account and on any number of organizations, each its own
    // installation with its own access token — so this is a list, not a single pinned install.
    private List<Installation> resolvedInstallations;
    private Instant resolvedInstallationsAt = Instant.EPOCH;

    // Per-installation access tokens, keyed by installation id (each minted/refreshed independently).
    private final Map<Long, String> installationTokens = new HashMap<>();
    private final Map<Long, Instant> installationTokenExpiries = new HashMap<>();

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

    /** Forgets the cached installations and tokens so the next call re-resolves from scratch. */
    public synchronized GitHubStatus disconnect() {
        invalidate();
        return status();
    }

    /** Forces the next status read to re-check GitHub after an install/setup redirect. */
    public synchronized void refresh() {
        invalidate();
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
            List<Installation> installations = installations();
            if (installations.isEmpty()) {
                return new GitHubStatus(false, true, canInstall, "github-app", "",
                        "GitHub App is configured but not installed yet. Click Connect to install it "
                                + "on your account or organization.");
            }
            String account = installations.stream()
                    .map(Installation::account)
                    .filter(a -> a != null && !a.isBlank())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            String where = account.isBlank() ? "" : " (" + account + ")";
            return new GitHubStatus(true, true, true, "github-app", account,
                    "Connected to GitHub as a GitHub App" + where + ". Click Connect to add another "
                            + "account or organization.");
        } catch (Exception e) {
            log.warn("GitHub App status check failed: {}", e.getMessage());
            return new GitHubStatus(false, true, canInstall, "github-app", "",
                    "GitHub App is configured but the connection failed: " + e.getMessage());
        }
    }

    /** A valid installation access token for the {@code github_*} tools, or empty when not connected. */
    public synchronized Optional<String> installationToken() {
        return installationToken(null);
    }

    /**
     * A valid installation access token scoped to the installation that owns {@code owner} (a user or
     * org login), falling back to the first installation when {@code owner} is null or unmatched.
     * Returns empty when the App is unconfigured or no installation can serve the request.
     */
    public synchronized Optional<String> installationToken(String owner) {
        if (!properties.configured()) {
            return Optional.empty();
        }
        try {
            Long installationId = installationForOwner(owner);
            if (installationId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(tokenForInstallation(installationId));
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
     * Lists every repository reachable across <b>all</b> of the App's installations — the user's
     * personal account plus each organization the App is installed on. Each installation is queried
     * with its own token and the results are merged, de-duplicated, and sorted by full name.
     */
    public synchronized List<GitHubRepositoryRef> listRepositories() throws Exception {
        List<GitHubRepositoryRef> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Installation installation : installations()) {
            int page = 1;
            while (true) {
                HttpResponse<String> response = api("GET",
                        "/installation/repositories?per_page=100&page=" + page, null, installation.id());
                if (response.statusCode() >= 300) {
                    throw new IllegalStateException("repository listing failed: HTTP " + response.statusCode()
                            + " " + response.body());
                }
                JsonNode repos = objectMapper.readTree(response.body()).path("repositories");
                if (!repos.isArray() || repos.isEmpty()) {
                    break;
                }
                for (JsonNode repo : repos) {
                    String fullName = repo.path("full_name").asText("");
                    String name = repo.path("name").asText("");
                    String owner = repo.path("owner").path("login").asText("");
                    if (fullName.isBlank() || name.isBlank() || owner.isBlank()) {
                        continue;
                    }
                    if (!seen.add(fullName.toLowerCase())) {
                        continue;
                    }
                    results.add(new GitHubRepositoryRef(
                            fullName,
                            name,
                            owner,
                            repo.path("private").asBoolean(),
                            repo.path("default_branch").asText(""),
                            repo.path("description").asText("")));
                }
                if (repos.size() < 100) {
                    break;
                }
                page++;
            }
        }
        results.sort(Comparator.comparing(r -> r.fullName().toLowerCase()));
        return results;
    }

    /** Fetches richer metadata for a single repository (language, stars, description, …). */
    public synchronized GitHubRepositoryDetail repositoryDetails(String owner, String repo) throws Exception {
        HttpResponse<String> response = api("GET", "/repos/" + owner + "/" + repo, null);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("repository lookup failed: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        JsonNode r = objectMapper.readTree(response.body());
        return new GitHubRepositoryDetail(
                r.path("full_name").asText(""),
                r.path("name").asText(""),
                r.path("owner").path("login").asText(""),
                r.path("private").asBoolean(),
                r.path("default_branch").asText(""),
                textOrNull(r, "description"),
                textOrNull(r, "language"),
                r.path("stargazers_count").asInt(0),
                r.path("forks_count").asInt(0),
                r.path("open_issues_count").asInt(0),
                textOrNull(r, "html_url"),
                textOrNull(r, "pushed_at"));
    }

    public synchronized List<String> listBranches(String owner, String repo) throws Exception {
        HttpResponse<String> response = api("GET", "/repos/" + owner + "/" + repo + "/branches?per_page=100", null);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("branch listing failed: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        JsonNode branches = objectMapper.readTree(response.body());
        List<String> results = new ArrayList<>();
        if (!branches.isArray()) {
            return results;
        }
        for (JsonNode branch : branches) {
            String name = branch.path("name").asText("");
            if (!name.isBlank()) {
                results.add(name);
            }
        }
        return results;
    }

    public String cloneUrl(String fullName) {
        return properties.webBaseUrl() + "/" + fullName + ".git";
    }

    /**
     * Calls the GitHub REST API, routing the request to the installation that should serve it: for
     * {@code /repos/{owner}/...} paths that's the installation owning {@code owner}, otherwise the
     * first installation. This keeps per-repo tools (create PR/issue, branches) working across orgs
     * without each having to know which installation a repository belongs to.
     */
    public synchronized HttpResponse<String> api(String method, String path, String jsonBody) throws Exception {
        Long installationId = installationForOwner(repoOwner(path));
        if (installationId == null) {
            throw new IllegalStateException("GitHub App is not installed; no installation available.");
        }
        return api(method, path, jsonBody, installationId);
    }

    private HttpResponse<String> api(String method, String path, String jsonBody, long installationId)
            throws Exception {
        String token = tokenForInstallation(installationId);
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

    /** Mints/refreshes the access token for a specific installation, or null if it cannot be obtained. */
    private String tokenForInstallation(long installationId) throws Exception {
        String cached = installationTokens.get(installationId);
        Instant expiry = installationTokenExpiries.get(installationId);
        if (cached != null && expiry != null
                && Instant.now().isBefore(expiry.minus(TOKEN_REFRESH_SKEW))) {
            return cached;
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
        String token = root.path("token").asText("");
        if (token.isBlank()) {
            return null;
        }
        String expiresAt = root.path("expires_at").asText("");
        Instant tokenExpiry = expiresAt.isBlank() ? Instant.now().plus(Duration.ofMinutes(55))
                : Instant.parse(expiresAt);
        installationTokens.put(installationId, token);
        installationTokenExpiries.put(installationId, tokenExpiry);
        return token;
    }

    /** The installation that owns {@code owner} (a user/org login), or the first one as a fallback. */
    private Long installationForOwner(String owner) throws Exception {
        List<Installation> installations = installations();
        if (installations.isEmpty()) {
            return null;
        }
        if (owner != null && !owner.isBlank()) {
            for (Installation installation : installations) {
                if (owner.equalsIgnoreCase(installation.account())) {
                    return installation.id();
                }
            }
        }
        return installations.get(0).id();
    }

    /** Extracts {@code owner} from a {@code /repos/{owner}/{repo}...} path, or null for other paths. */
    private static String repoOwner(String path) {
        if (path == null || !path.startsWith("/repos/")) {
            return null;
        }
        String rest = path.substring("/repos/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return rest.substring(0, slash);
    }

    /** All installations of the App (the pinned one, or every install), cached briefly. */
    private List<Installation> installations() throws Exception {
        if (resolvedInstallations != null
                && Duration.between(resolvedInstallationsAt, Instant.now()).compareTo(STATUS_CACHE) < 0) {
            return resolvedInstallations;
        }
        List<Installation> resolved = resolveInstallations();
        resolvedInstallations = resolved;
        resolvedInstallationsAt = Instant.now();
        return resolved;
    }

    /** Resolves every installation of the App: the pinned one when configured, else all of them. */
    private List<Installation> resolveInstallations() throws Exception {
        if (properties.installationId() != null && properties.installationId() > 0) {
            long id = properties.installationId();
            return List.of(new Installation(id, lookupAccount(id)));
        }

        String jwt = buildAppJwt();
        List<Installation> result = new ArrayList<>();
        int page = 1;
        while (true) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.apiBaseUrl() + "/app/installations?per_page=100&page=" + page))
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
                break; // No (more) installations.
            }
            for (JsonNode installation : installations) {
                long id = installation.path("id").asLong();
                String account = installation.path("account").path("login").asText("");
                if (id > 0) {
                    result.add(new Installation(id, account));
                }
            }
            if (installations.size() < 100) {
                break;
            }
            page++;
        }
        return result;
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
        resolvedInstallations = null;
        resolvedInstallationsAt = Instant.EPOCH;
        installationTokens.clear();
        installationTokenExpiries.clear();
    }

    /** One installation of the App: its numeric id and the account (user or org) login it lives on. */
    private record Installation(long id, String account) {
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Returns the text value at {@code key}, or {@code null} when absent, blank, or JSON null. */
    private static String textOrNull(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    public record GitHubRepositoryRef(
            String fullName,
            String name,
            String owner,
            boolean privateRepo,
            String defaultBranch,
            String description) {
    }

    public record GitHubRepositoryDetail(
            String fullName,
            String name,
            String owner,
            boolean privateRepo,
            String defaultBranch,
            String description,
            String language,
            int stargazers,
            int forks,
            int openIssues,
            String htmlUrl,
            String pushedAt) {
    }
}
