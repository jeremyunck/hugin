package com.example.integration.github;

/** Request body for starting a GitHub App install/connect flow. */
public record GitHubConnectRequest(String returnTo) {}
