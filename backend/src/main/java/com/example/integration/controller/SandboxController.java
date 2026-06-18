package com.example.integration.controller;

import com.example.agent.model.FileNode;
import com.example.agent.model.SandboxInfo;
import com.example.integration.github.GitHubAppService;
import com.example.integration.service.DockerSandboxManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for per-session Docker sandboxes.
 *
 * <pre>
 * POST   /api/sandboxes        start a new sandbox (Docker container + workspace); returns its id
 * GET    /api/sandboxes        list all live sandboxes
 * GET    /api/sandboxes/{id}   get one sandbox's metadata
 * DELETE /api/sandboxes/{id}   stop + remove the sandbox and its workspace
 * </pre>
 *
 * <p>A client starts a session with {@code POST /api/sandboxes}, then sends the returned {@code id}
 * back as {@code sandboxId} on {@code /api/agent/chat} and {@code /api/agent/stream} so the agent's
 * tools run inside that sandbox.
 */
@RestController
@RequestMapping("/api/sandboxes")
public class SandboxController {

    /** Optional body for {@code POST /api/sandboxes}; {@code image} overrides the configured default. */
    public record CreateSandboxRequest(String image) {}
    public record CreateGitHubSandboxRequest(String image, String repoFullName, String branch) {}

    private final DockerSandboxManager sandboxManager;
    private final GitHubAppService github;

    public SandboxController(DockerSandboxManager sandboxManager, GitHubAppService github) {
        this.sandboxManager = sandboxManager;
        this.github = github;
    }

    @PostMapping
    public ResponseEntity<SandboxInfo> create(@RequestBody(required = false) CreateSandboxRequest req) {
        String image = req != null ? req.image() : null;
        SandboxInfo info = sandboxManager.create(image);
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    @PostMapping("/github")
    public ResponseEntity<SandboxInfo> createGitHubSandbox(@RequestBody CreateGitHubSandboxRequest req) {
        if (req == null || req.repoFullName() == null || req.repoFullName().isBlank()) {
            throw new IllegalStateException("A GitHub repository must be selected.");
        }
        if (req.branch() == null || req.branch().isBlank()) {
            throw new IllegalStateException("A GitHub branch must be selected.");
        }
        String[] parts = req.repoFullName().split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalStateException("GitHub repository must be in owner/repo format.");
        }
        String accessToken = github.installationToken()
                .orElseThrow(() -> new IllegalStateException("GitHub is not connected."));
        SandboxInfo info = sandboxManager.createGitHubRepoSandbox(
                req.image(),
                github.cloneUrl(req.repoFullName()),
                parts[1],
                req.branch(),
                accessToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    @GetMapping
    public List<SandboxInfo> list() {
        return sandboxManager.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SandboxInfo> get(@PathVariable String id) {
        return sandboxManager.get(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/files")
    public ResponseEntity<List<FileNode>> files(@PathVariable String id) {
        return sandboxManager.listFiles(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (sandboxManager.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        sandboxManager.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Sandbox unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
