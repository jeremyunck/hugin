package com.example.integration.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.DocsScopes;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Comparator;

/**
 * Builds authenticated Google {@link Docs}, {@link Sheets}, {@link Drive}, {@link Calendar} and
 * {@link Gmail} service clients from a service-account credential, and provides a small
 * {@link #shareFile} helper.
 *
 * <p>This is the single place that touches the Google API SDK and credentials. The individual
 * {@code google_*} tools depend on it and treat {@link #isConfigured()} as a gate: when no credentials
 * are configured the tools degrade gracefully (returning an explanatory message) instead of throwing,
 * mirroring how {@code web_search} behaves without an API key.
 *
 * <p>Service clients are built lazily and cached: the first tool call that needs one constructs it,
 * subsequent calls reuse it. Construction is synchronized so concurrent agent requests can't race.
 */
@Component
public class GoogleWorkspaceClientFactory {

    private static final Logger log = LoggerFactory.getLogger(GoogleWorkspaceClientFactory.class);
    private static final String OAUTH_USER_ID = "hugin";

    /** Scopes for the Google APIs the built-in Workspace tools use. */
    private static final List<String> SCOPES = List.of(
            CalendarScopes.CALENDAR,
            DocsScopes.DOCUMENTS,
            GmailScopes.GMAIL_READONLY,
            GmailScopes.GMAIL_SEND,
            SheetsScopes.SPREADSHEETS,
            DriveScopes.DRIVE);

    private final GoogleWorkspaceProperties properties;
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private NetHttpTransport transport;
    private HttpRequestInitializer requestInitializer;
    private GoogleAuthorizationCodeFlow cachedOauthFlow;
    private Calendar calendar;
    private Docs docs;
    private Gmail gmail;
    private Sheets sheets;
    private Drive drive;

    public GoogleWorkspaceClientFactory(GoogleWorkspaceProperties properties) {
        this.properties = properties;
    }

    /** Short TTL so repeated availability checks (one per Google tool, per agent request) avoid
     * re-reading the OAuth token store on every call while still reflecting connect/disconnect quickly. */
    private static final long AVAILABILITY_CACHE_MS = 5_000;
    private volatile boolean cachedAvailable;
    private volatile long availabilityCheckedAt;

    /** True when OAuth client secrets or a service-account credentials file is configured and present. */
    public boolean isConfigured() {
        return hasOauthClientSecrets() || hasServiceAccountCredentials();
    }

    /**
     * Whether the Google Workspace tools are ready to use right now (configured and, for OAuth,
     * consented). Used by each {@code google_*} tool's {@link com.example.agent.tool.LocalTool#isAvailable()}
     * so the tools are only advertised to the model once the integration is actually connected.
     * The result is cached for {@value #AVAILABILITY_CACHE_MS}ms because {@link #status()} reads the
     * OAuth token store from disk and is called once per tool when the agent builds its tool list.
     */
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now - availabilityCheckedAt < AVAILABILITY_CACHE_MS) {
            return cachedAvailable;
        }
        boolean active;
        try {
            active = status().active();
        } catch (Exception e) {
            log.debug("Google availability check failed: {}", e.getMessage());
            active = false;
        }
        cachedAvailable = active;
        availabilityCheckedAt = now;
        return active;
    }

    /** Resets the cached availability so a connect/disconnect is reflected on the next check. */
    private void invalidateAvailability() {
        availabilityCheckedAt = 0L;
    }

    /**
     * Reports whether Google Workspace is ready right now and whether the UI can retry auth.
     */
    public synchronized GoogleWorkspaceStatus status() {
        if (hasOauthClientSecrets()) {
            try {
                if (hasOAuthCredential()) {
                    return new GoogleWorkspaceStatus(
                            true,
                            true,
                            true,
                            "oauth",
                            "Google OAuth is connected and the Drive, Docs, Sheets, Calendar, and Gmail tools are ready.");
                }
                return new GoogleWorkspaceStatus(
                        false,
                        true,
                        true,
                        "oauth",
                        "Google OAuth client secrets are configured, but consent has not completed yet. Click Reconnect to open the browser flow.");
            } catch (Exception e) {
                return new GoogleWorkspaceStatus(
                        false,
                        true,
                        true,
                        "oauth",
                        "Google OAuth is configured, but the consent store could not be read: " + e.getMessage());
            }
        }

        if (hasServiceAccountCredentials()) {
            return new GoogleWorkspaceStatus(
                    true,
                    true,
                    false,
                    "service-account",
                    "Google Workspace is connected with a service-account key.");
        }

        return new GoogleWorkspaceStatus(
                false,
                false,
                false,
                "none",
                "Google Workspace is not configured. Set GOOGLE_OAUTH_CLIENT_SECRETS_FILE or GOOGLE_APPLICATION_CREDENTIALS.");
    }

    /**
     * Forces Google auth to reconnect. For OAuth, this clears cached consent and re-runs the browser flow.
     * For service-account setups, it refreshes the cached client wrappers and returns the latest status.
     */
    public synchronized GoogleWorkspaceStatus reconnect() throws IOException, GeneralSecurityException {
        if (hasOauthClientSecrets()) {
            clearOauthTokenCache();
            requestInitializer = authorizeViaOAuth();
            clearCachedClients();
            return status();
        }

        if (hasServiceAccountCredentials()) {
            requestInitializer = new HttpCredentialsAdapter(serviceAccountCredentials());
            clearCachedClients();
            return status();
        }

        return status();
    }

    /**
     * Disconnects an OAuth-backed Google Workspace session by clearing the cached consent tokens.
     * Service-account setups remain configured because there is no user session to revoke here.
     */
    public synchronized GoogleWorkspaceStatus disconnect() throws IOException {
        if (hasOauthClientSecrets()) {
            clearOauthTokenCache();
            requestInitializer = null;
            clearCachedClients();
        }
        return status();
    }

    /**
     * Starts an OAuth reconnect and returns the URL the browser should open.
     */
    public synchronized GoogleReconnectResponse beginReconnect(String returnTo) throws IOException, GeneralSecurityException {
        if (hasOauthClientSecrets()) {
            clearOauthTokenCache();
            clearCachedClients();

            GoogleAuthorizationCodeFlow flow = oauthFlow();
            String landingPage = buildLandingPage(returnTo, true);
            String failurePage = buildLandingPage(returnTo, false);
            LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                    .setHost("127.0.0.1")
                    .setPort(properties.oauthLocalServerPort())
                    .setLandingPages(landingPage, failurePage)
                    .build();
            String authUrl = flow.newAuthorizationUrl()
                    .setRedirectUri(receiver.getRedirectUri())
                    .build();

            CompletableFuture.runAsync(() -> {
                try {
                    requestInitializer = new AuthorizationCodeInstalledApp(flow, receiver).authorize(OAUTH_USER_ID);
                    clearCachedClients();
                } catch (Exception e) {
                    log.warn("Google OAuth reconnect failed: {}", e.getMessage());
                }
            });

            return new GoogleReconnectResponse(status(), authUrl);
        }

        if (hasServiceAccountCredentials()) {
            requestInitializer = new HttpCredentialsAdapter(serviceAccountCredentials());
            clearCachedClients();
            return new GoogleReconnectResponse(status(), null);
        }

        return new GoogleReconnectResponse(status(), null);
    }

    public synchronized GoogleReconnectResponse beginReconnect() throws IOException, GeneralSecurityException {
        return beginReconnect(null);
    }

    /** A Google API operation run inside {@link #guarded(GoogleCall)}. */
    @FunctionalInterface
    public interface GoogleCall {
        String run() throws Exception;
    }

    /**
     * Runs a Google API operation behind the two checks every tool needs: it returns the
     * {@link #unavailableMessage()} when no credentials are configured, and converts any Google
     * API / credential failure into a concise, actionable message via {@link GoogleErrors} instead
     * of letting a raw exception propagate. This keeps the individual tools free of boilerplate and
     * gives the model useful guidance (e.g. "share the file with the service account") on failure.
     */
    public String guarded(GoogleCall call) {
        if (!isConfigured()) {
            return unavailableMessage();
        }
        try {
            return call.run();
        } catch (Exception e) {
            log.warn("Google Workspace tool call failed: {}", e.getMessage());
            return GoogleErrors.describe(e);
        }
    }

    /** A one-line explanation shown by the tools when {@link #isConfigured()} is false. */
    public String unavailableMessage() {
        return "Google Workspace tools are unavailable: no Google OAuth client secrets or service-account "
                + "credentials configured. Set GOOGLE_OAUTH_CLIENT_SECRETS_FILE for OAuth (preferred) or "
                + "GOOGLE_APPLICATION_CREDENTIALS for a service-account JSON key.";
    }

    public synchronized Docs docs() throws IOException, GeneralSecurityException {
        if (docs == null) {
            docs = new Docs.Builder(transport(), jsonFactory, requestInitializer())
                    .setApplicationName(properties.applicationName())
                    .build();
        }
        return docs;
    }

    public synchronized Sheets sheets() throws IOException, GeneralSecurityException {
        if (sheets == null) {
            sheets = new Sheets.Builder(transport(), jsonFactory, requestInitializer())
                    .setApplicationName(properties.applicationName())
                    .build();
        }
        return sheets;
    }

    public synchronized Drive drive() throws IOException, GeneralSecurityException {
        if (drive == null) {
            drive = new Drive.Builder(transport(), jsonFactory, requestInitializer())
                    .setApplicationName(properties.applicationName())
                    .build();
        }
        return drive;
    }

    public synchronized Calendar calendar() throws IOException, GeneralSecurityException {
        if (calendar == null) {
            calendar = new Calendar.Builder(transport(), jsonFactory, requestInitializer())
                    .setApplicationName(properties.applicationName())
                    .build();
        }
        return calendar;
    }

    public synchronized Gmail gmail() throws IOException, GeneralSecurityException {
        if (gmail == null) {
            gmail = new Gmail.Builder(transport(), jsonFactory, requestInitializer())
                    .setApplicationName(properties.applicationName())
                    .build();
        }
        return gmail;
    }

    /**
     * Grants {@code email} access to a Drive file (a Doc or Sheet is a Drive file) at the given role
     * ({@code writer}, {@code reader}, {@code commenter}). Returns null on success, or an error string
     * describing why sharing failed so the calling tool can surface it without aborting the whole call.
     */
    public String shareFile(String fileId, String email, String role) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            Permission permission = new Permission()
                    .setType("user")
                    .setRole(role == null || role.isBlank() ? "writer" : role)
                    .setEmailAddress(email);
            drive().permissions().create(fileId, permission)
                    .setSendNotificationEmail(false)
                    .setFields("id")
                    .execute();
            return null;
        } catch (Exception e) {
            log.warn("Failed to share {} with {}: {}", fileId, email, e.getMessage());
            return "could not share with " + email + ": " + e.getMessage();
        }
    }

    /** The email this factory shares newly created files with by default (may be blank). */
    public String defaultShareWith() {
        return properties.defaultShareWith();
    }

    private synchronized NetHttpTransport transport() throws GeneralSecurityException, IOException {
        if (transport == null) {
            transport = GoogleNetHttpTransport.newTrustedTransport();
        }
        return transport;
    }

    private HttpRequestInitializer requestInitializer() throws IOException, GeneralSecurityException {
        if (requestInitializer == null) {
            requestInitializer = createRequestInitializer();
        }
        return requestInitializer;
    }

    private HttpRequestInitializer createRequestInitializer() throws IOException, GeneralSecurityException {
        if (hasOauthClientSecrets()) {
            if (GraphicsEnvironment.isHeadless()) {
                throw new IOException(
                        "Google OAuth client secrets are configured, but this process is running headless. "
                                + "Use a service-account JSON key instead on servers without a GUI.");
            }
            return authorizeViaOAuth();
        }
        if (hasServiceAccountCredentials()) {
            return new HttpCredentialsAdapter(serviceAccountCredentials());
        }
        throw new IOException("No Google OAuth client secrets or service-account credentials configured.");
    }

    private void clearCachedClients() {
        invalidateAvailability();
        docs = null;
        sheets = null;
        drive = null;
        calendar = null;
        gmail = null;
        transport = null;
        cachedOauthFlow = null;
    }

    private HttpRequestInitializer authorizeViaOAuth() throws IOException, GeneralSecurityException {
        GoogleAuthorizationCodeFlow flow = oauthFlow();
        Credential existing = flow.loadCredential(OAUTH_USER_ID);
        if (existing != null) {
            return existing;
        }
        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setHost("127.0.0.1")
                .setPort(properties.oauthLocalServerPort())
                .build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize(OAUTH_USER_ID);
    }

    private String buildLandingPage(String returnTo, boolean success) {
        String target = normalizeReturnTo(returnTo);
        String headline = success ? "Google connected" : "Google connection failed";
        String message = success
                ? "Returning you to Hugin..."
                : "The reconnect flow did not finish cleanly. Returning you to Hugin...";
        String escapedTarget = escapeHtml(target);
        String escapedMessage = escapeHtml(message);
        String escapedHeadline = escapeHtml(headline);
        String jsTarget = escapeJsString(target);

        return """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <title>%s</title>
                    <meta http-equiv="refresh" content="0;url=%s" />
                    <style>
                      body {
                        font-family: Inter, Arial, sans-serif;
                        background: #0b1220;
                        color: #e5eefc;
                        margin: 0;
                        min-height: 100vh;
                        display: grid;
                        place-items: center;
                      }
                      .card {
                        max-width: 520px;
                        padding: 24px 28px;
                        border-radius: 16px;
                        background: rgba(15, 23, 42, 0.9);
                        border: 1px solid rgba(148, 163, 184, 0.24);
                        box-shadow: 0 24px 80px rgba(15, 23, 42, 0.45);
                      }
                      h1 {
                        margin: 0 0 8px;
                        font-size: 24px;
                      }
                      p {
                        margin: 0 0 12px;
                        line-height: 1.5;
                        color: #bfd0ea;
                      }
                      code {
                        display: block;
                        word-break: break-all;
                        padding: 10px 12px;
                        border-radius: 10px;
                        background: rgba(30, 41, 59, 0.9);
                        color: #dbeafe;
                      }
                    </style>
                  </head>
                  <body>
                    <div class="card">
                      <h1>%s</h1>
                      <p>%s</p>
                      <code>%s</code>
                    </div>
                    <script>
                      window.setTimeout(() => window.location.replace(%s), 100);
                    </script>
                  </body>
                </html>
                """.formatted(escapedHeadline, escapedTarget, escapedHeadline, escapedMessage, escapedTarget, jsTarget);
    }

    private String normalizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return "http://localhost:5173/";
        }
        try {
            URI uri = URI.create(returnTo.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return "http://localhost:5173/";
            }
            return returnTo.trim();
        } catch (IllegalArgumentException e) {
            return "http://localhost:5173/";
        }
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String escapeJsString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private synchronized void clearOauthTokenCache() throws IOException {
        // Drop the cached flow: its FileDataStoreFactory keeps token files cached in memory, so a
        // flow built before the wipe would still report the deleted credential as present.
        cachedOauthFlow = null;
        Path tokenDir = expandHome(properties.oauthTokenDir());
        if (!Files.exists(tokenDir)) {
            return;
        }
        try (var paths = Files.walk(tokenDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(tokenDir))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to clear Google OAuth token cache", e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private boolean hasOAuthCredential() throws IOException, GeneralSecurityException {
        return oauthFlow().loadCredential(OAUTH_USER_ID) != null;
    }

    /**
     * Builds (and caches) the OAuth flow. The flow is rebuilt after {@link #clearCachedClients()}
     * or {@link #clearOauthTokenCache()}, since its file-backed credential store caches token files
     * in memory and would otherwise serve stale state. Caching avoids re-reading and re-parsing the
     * client secrets file on every status check or tool call.
     */
    private synchronized GoogleAuthorizationCodeFlow oauthFlow() throws IOException, GeneralSecurityException {
        if (cachedOauthFlow != null) {
            return cachedOauthFlow;
        }
        Path secretsPath = expandHome(properties.oauthClientSecretsFile());
        Path tokenDir = expandHome(properties.oauthTokenDir());
        Files.createDirectories(tokenDir);

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(secretsPath.toFile()),
                StandardCharsets.UTF_8)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
            cachedOauthFlow = new GoogleAuthorizationCodeFlow.Builder(
                    transport(), jsonFactory, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(tokenDir.toFile()))
                    .setAccessType("offline")
                    .build();
            return cachedOauthFlow;
        }
    }

    /**
     * Loads the service-account credentials from the configured file, applies optional domain-wide
     * delegation, and scopes them to the Docs/Sheets/Drive APIs.
     */
    private GoogleCredentials serviceAccountCredentials() throws IOException {
        GoogleCredentials base;
        try (InputStream in = new FileInputStream(expandHome(properties.credentialsFile()).toFile())) {
            base = GoogleCredentials.fromStream(in);
        }

        String impersonate = properties.impersonateUser();
        if (impersonate != null && !impersonate.isBlank() && base instanceof ServiceAccountCredentials sac) {
            base = sac.createDelegated(impersonate);
        }
        return base.createScoped(SCOPES);
    }

    private boolean hasOauthClientSecrets() {
        String file = properties.oauthClientSecretsFile();
        return file != null && !file.isBlank() && Files.exists(expandHome(file));
    }

    private boolean hasServiceAccountCredentials() {
        String file = properties.credentialsFile();
        return file != null && !file.isBlank() && Files.exists(expandHome(file));
    }

    private Path expandHome(String raw) {
        if (raw == null || raw.isBlank()) {
            return Path.of(raw == null ? "" : raw);
        }
        String expanded = raw.startsWith("~/")
                ? System.getProperty("user.home") + raw.substring(1)
                : raw;
        return Path.of(expanded);
    }
}
