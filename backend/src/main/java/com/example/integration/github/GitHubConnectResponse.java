package com.example.integration.github;

/**
 * Response from the connect endpoint.
 *
 * <p>{@code installUrl} is the GitHub App's public installation page. The UI opens it in a new tab so
 * the user can install the App on the account/repositories they want Bouw to access; once installed,
 * {@link GitHubStatus#active()} flips to true on the next status poll.
 */
public record GitHubConnectResponse(
        GitHubStatus status,
        String installUrl
) {}
