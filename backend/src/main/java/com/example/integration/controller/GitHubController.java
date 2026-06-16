package com.example.integration.controller;

import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubConnectRequest;
import com.example.integration.github.GitHubConnectResponse;
import com.example.integration.github.GitHubStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for checking and connecting the GitHub App integration. */
@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private final GitHubAppService github;

    public GitHubController(GitHubAppService github) {
        this.github = github;
    }

    @GetMapping("/status")
    public GitHubStatus status() {
        return github.status();
    }

    @PostMapping("/connect")
    public GitHubConnectResponse connect(@RequestBody(required = false) GitHubConnectRequest request) {
        return github.beginConnect(request == null ? null : request.returnTo());
    }

    @PostMapping("/disconnect")
    public GitHubStatus disconnect() {
        return github.disconnect();
    }
}
