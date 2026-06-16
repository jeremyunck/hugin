package com.example.integration.controller;

import com.example.integration.github.GitHubAppService;
import com.example.integration.github.GitHubConnectRequest;
import com.example.integration.github.GitHubConnectResponse;
import com.example.integration.github.GitHubStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String installation_id,
                                         @RequestParam(required = false) String setup_action) {
        return finishInstall(installation_id, setup_action);
    }

    @GetMapping("/setup")
    public ResponseEntity<Void> setup(@RequestParam(required = false) String installation_id,
                                      @RequestParam(required = false) String setup_action) {
        return finishInstall(installation_id, setup_action);
    }

    @PostMapping("/connect")
    public GitHubConnectResponse connect(@RequestBody(required = false) GitHubConnectRequest request) {
        return github.beginConnect(request == null ? null : request.returnTo());
    }

    @PostMapping("/disconnect")
    public GitHubStatus disconnect() {
        return github.disconnect();
    }

    private ResponseEntity<Void> finishInstall(String installationId, String setupAction) {
        github.refresh();
        String target = buildRedirect(installationId, setupAction);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .build();
    }

    private static String buildRedirect(String installationId, String setupAction) {
        StringBuilder target = new StringBuilder("/?screen=integrations&github=installed");
        if (installationId != null && !installationId.isBlank()) {
            target.append("&installation_id=")
                    .append(URLEncoder.encode(installationId, StandardCharsets.UTF_8));
        }
        if (setupAction != null && !setupAction.isBlank()) {
            target.append("&setup_action=")
                    .append(URLEncoder.encode(setupAction, StandardCharsets.UTF_8));
        }
        return target.toString();
    }
}
