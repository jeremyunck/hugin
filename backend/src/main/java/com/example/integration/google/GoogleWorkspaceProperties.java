package com.example.integration.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Google Workspace tools (Docs, Sheets, Drive, Calendar and Gmail).
 *
 * <ul>
 *   <li>{@code oauthClientSecretsFile} — path to a Google OAuth client-secrets JSON file. This is the
 *       preferred way to authenticate a personal Bouw install. Create a desktop OAuth client in a
 *       Google Cloud project, enable the Docs/Sheets/Drive/Gmail APIs, download the JSON, and point
 *       this at it. When first used, Bouw opens the browser for user consent and stores refresh
 *       tokens in {@code oauthTokenDir}.</li>
 *   <li>{@code oauthTokenDir} — directory where OAuth refresh tokens are cached. Defaults to a
 *       directory under {@code ~/.bouw} so the consent flow only needs to run once.</li>
 *   <li>{@code oauthLocalServerPort} — local loopback port used for the OAuth callback during the
 *       initial consent flow on a desktop dev environment.</li>
 *   <li>{@code oauthCallbackUrl} — the public callback URL registered in the Google Cloud Console
 *       as the OAuth redirect URI. Set this to the deployed server's callback endpoint
 *       (e.g. {@code https://bouw.thecognitivejunction.com/Callback}) for server-side OAuth flows.
 *       When blank, a local loopback server ({@code localhost:oauthLocalServerPort}) is used instead,
 *       which is suitable for local development only.</li>
 *   <li>{@code credentialsFile} — optional path to a Google <b>service-account</b> JSON key file for
 *       legacy Workspace/domain-wide delegation setups. When blank/missing, the oauth_* flow is used
 *       if configured; otherwise the google_* tools report themselves as unavailable rather than
 *       failing startup.</li>
 *   <li>{@code applicationName} — the application name sent to Google APIs (cosmetic, for quotas/logs).</li>
 *   <li>{@code impersonateUser} — optional. For Google Workspace domains using
 *       <a href="https://developers.google.com/identity/protocols/oauth2/service-account#delegatingauthority">
 *       domain-wide delegation</a>, the email of the user the service account should act as. Leave blank
 *       for standard service-account access (where files must be explicitly shared with the service
 *       account's own email).</li>
 *   <li>{@code defaultShareWith} — optional email address that newly created docs/sheets are
 *       automatically shared with (as a writer). Without this, files created by the service account are
 *       owned by — and only visible to — the service account, so a human can't open them. Per-call
 *       {@code share_with} arguments override this.</li>
 * </ul>
 */
@ConfigurationProperties("google")
public record GoogleWorkspaceProperties(
        String credentialsFile,
        String oauthClientSecretsFile,
        String oauthTokenDir,
        Integer oauthLocalServerPort,
        String oauthCallbackUrl,
        String applicationName,
        String impersonateUser,
        String defaultShareWith) {

    public GoogleWorkspaceProperties {
        if (credentialsFile == null) {
            credentialsFile = "";
        }
        if (oauthClientSecretsFile == null) {
            oauthClientSecretsFile = "";
        }
        if (oauthTokenDir == null || oauthTokenDir.isBlank()) {
            oauthTokenDir = System.getProperty("user.home") + "/.bouw/google-oauth";
        }
        if (oauthLocalServerPort == null || oauthLocalServerPort <= 0) {
            oauthLocalServerPort = 8765;
        }
        if (oauthCallbackUrl == null) {
            oauthCallbackUrl = "";
        }
        if (applicationName == null || applicationName.isBlank()) {
            applicationName = "Bouw";
        }
        if (impersonateUser == null) {
            impersonateUser = "";
        }
        if (defaultShareWith == null) {
            defaultShareWith = "";
        }
    }
}
