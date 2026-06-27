package com.example.integration.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the GitHub integration, authenticated as a <b>GitHub App</b>.
 *
 * <p>Bouw authenticates to the GitHub REST API as a GitHub App rather than with a personal access
 * token. The flow is:
 * <ol>
 *   <li>The App is identified by its numeric {@code appId} and an RSA {@code privateKey}
 *       (PEM, downloaded from the App's settings page). These sign a short-lived JWT.</li>
 *   <li>The App is <i>installed</i> on a user/org. Each installation has an id; Bouw exchanges the
 *       App JWT for a per-installation access token used to call the API on the installation's
 *       behalf.</li>
 * </ol>
 *
 * <ul>
 *   <li>{@code appId} — the GitHub App's numeric ID (App settings → "About").</li>
 *   <li>{@code appSlug} — the App's URL slug, used to build the public install URL
 *       ({@code https://github.com/apps/<slug>/installations/new}) the UI "Connect" button opens.</li>
 *   <li>{@code privateKey} — the App's private key as inline PEM. Takes precedence over
 *       {@code privateKeyPath} when both are set.</li>
 *   <li>{@code privateKeyPath} — path to a {@code .pem} file containing the App's private key
 *       ({@code ~/} is expanded). Either PKCS#1 ("BEGIN RSA PRIVATE KEY") or PKCS#8
 *       ("BEGIN PRIVATE KEY") encodings are accepted.</li>
 *   <li>{@code installationId} — optional. Pin a specific installation. When blank, Bouw discovers
 *       the first installation of the App automatically.</li>
 *   <li>{@code apiBaseUrl} — GitHub REST API root (override for GitHub Enterprise).</li>
 *   <li>{@code webBaseUrl} — GitHub web root used to build the install URL (override for Enterprise).</li>
 * </ul>
 *
 * <p>When no App credentials are configured the integration reports itself as not connected (no
 * startup failure) and the {@code github_*} tools are not advertised to the model.
 */
@ConfigurationProperties("github")
public record GitHubProperties(
        String appId,
        String appSlug,
        String privateKey,
        String privateKeyPath,
        Long installationId,
        String apiBaseUrl,
        String webBaseUrl) {

    public GitHubProperties {
        if (appId == null) {
            appId = "";
        }
        if (appSlug == null) {
            appSlug = "";
        }
        if (privateKey == null) {
            privateKey = "";
        }
        if (privateKeyPath == null) {
            privateKeyPath = "";
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.github.com";
        }
        // Trim a trailing slash so URL building is predictable.
        apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        if (webBaseUrl == null || webBaseUrl.isBlank()) {
            webBaseUrl = "https://github.com";
        }
        webBaseUrl = webBaseUrl.replaceAll("/+$", "");
    }

    /** Whether the App credentials needed to mint a JWT are present. */
    public boolean configured() {
        boolean hasKey = !privateKey.isBlank() || !privateKeyPath.isBlank();
        return !appId.isBlank() && hasKey;
    }
}
