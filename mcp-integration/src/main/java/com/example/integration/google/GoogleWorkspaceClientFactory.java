package com.example.integration.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
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
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Full read/write scopes for the three APIs the tools use. */
    private static final List<String> SCOPES = List.of(
            DocsScopes.DOCUMENTS,
            SheetsScopes.SPREADSHEETS,
            DriveScopes.DRIVE);

    private final GoogleWorkspaceProperties properties;
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private NetHttpTransport transport;
    private Docs docs;
    private Sheets sheets;
    private Drive drive;

    public GoogleWorkspaceClientFactory(GoogleWorkspaceProperties properties) {
        this.properties = properties;
    }

    /** True when a service-account credentials file is configured and present on disk. */
    public boolean isConfigured() {
        String file = properties.credentialsFile();
        return file != null && !file.isBlank() && Files.exists(Path.of(file));
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
        return "Google Workspace tools are unavailable: no service-account credentials configured. "
                + "Set the GOOGLE_APPLICATION_CREDENTIALS environment variable (or google.credentials-file) "
                + "to the path of a service-account JSON key with the Docs, Sheets and Drive APIs enabled.";
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

    private HttpRequestInitializer requestInitializer() throws IOException {
        return new HttpCredentialsAdapter(credentials());
    }

    /**
     * Loads the service-account credentials, applies optional domain-wide delegation, and scopes them
     * to the Docs/Sheets/Drive APIs.
     */
    private GoogleCredentials credentials() throws IOException {
        GoogleCredentials base;
        String file = properties.credentialsFile();
        if (file != null && !file.isBlank()) {
            try (InputStream in = new FileInputStream(file)) {
                base = GoogleCredentials.fromStream(in);
            }
        } else {
            base = GoogleCredentials.getApplicationDefault();
        }

        String impersonate = properties.impersonateUser();
        if (impersonate != null && !impersonate.isBlank() && base instanceof ServiceAccountCredentials sac) {
            base = sac.createDelegated(impersonate);
        }
        return base.createScoped(SCOPES);
    }
}
