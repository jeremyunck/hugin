package com.example.integration.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
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
import java.util.List;

/**
 * Builds authenticated Google {@link Docs}, {@link Sheets} and {@link Drive} service clients from a
 * service-account credential, and provides a small {@link #shareFile} helper.
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

    /** Full read/write scopes for the three APIs the tools use. */
    private static final List<String> SCOPES = List.of(
            CalendarScopes.CALENDAR,
            DocsScopes.DOCUMENTS,
            SheetsScopes.SPREADSHEETS,
            DriveScopes.DRIVE);

    private final GoogleWorkspaceProperties properties;
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private NetHttpTransport transport;
    private HttpRequestInitializer requestInitializer;
    private Calendar calendar;
    private Docs docs;
    private Sheets sheets;
    private Drive drive;

    public GoogleWorkspaceClientFactory(GoogleWorkspaceProperties properties) {
        this.properties = properties;
    }

    /** True when OAuth client secrets or a service-account credentials file is configured and present. */
    public boolean isConfigured() {
        return hasOauthClientSecrets() || hasServiceAccountCredentials();
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
            return authorizeViaOAuth();
        }
        if (hasServiceAccountCredentials()) {
            return new HttpCredentialsAdapter(serviceAccountCredentials());
        }
        throw new IOException("No Google OAuth client secrets or service-account credentials configured.");
    }

    private HttpRequestInitializer authorizeViaOAuth() throws IOException, GeneralSecurityException {
        Path secretsPath = expandHome(properties.oauthClientSecretsFile());
        Path tokenDir = expandHome(properties.oauthTokenDir());
        Files.createDirectories(tokenDir);

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(secretsPath.toFile()),
                StandardCharsets.UTF_8)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport(), jsonFactory, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(tokenDir.toFile()))
                    .setAccessType("offline")
                    .build();

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
