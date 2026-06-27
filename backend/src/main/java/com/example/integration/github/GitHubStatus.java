package com.example.integration.github;

/**
 * Snapshot of the GitHub App integration for the UI.
 *
 * <p>{@code active} means Bouw can currently mint an installation access token and call the GitHub
 * API right now (App credentials are configured <i>and</i> the App is installed somewhere).
 * {@code configured} means the App ID + private key are present but the App may not be installed yet.
 * {@code reconnectable} is true: the sidebar "Connect" button opens the App's public install page so
 * the user can install (or re-install) it.
 */
public record GitHubStatus(
        boolean active,
        boolean configured,
        boolean reconnectable,
        String authMode,
        String account,
        String message
) {}
