package com.example.integration.controller;

import com.example.integration.agent.UserAgent;
import com.example.integration.agent.UserAgentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent/agents")
public class UserAgentController {

    public record CreateAgentRequest(String name, String purpose, String model) {}

    public record UpdateAgentRequest(String name, String purpose, String model) {}

    public record AgentResponse(
            String id,
            String name,
            String purpose,
            String systemPrompt,
            String model,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    private final UserAgentService service;

    public UserAgentController(UserAgentService service) {
        this.service = service;
    }

    @GetMapping
    public List<AgentResponse> list(@AuthenticationPrincipal Jwt jwt) {
        String owner = owner(jwt);
        return service.list(owner).stream().map(UserAgentController::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<AgentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateAgentRequest request) {
        String owner = owner(jwt);
        UserAgent agent = service.create(owner, request.name(), request.purpose(), request.model());
        return ResponseEntity.ok(toResponse(agent));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        String owner = owner(jwt);
        return service.find(owner, id)
                .map(agent -> ResponseEntity.ok(toResponse(agent)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody UpdateAgentRequest request) {
        String owner = owner(jwt);
        UserAgent agent = service.update(owner, id, request.name(), request.purpose(), request.model());
        return ResponseEntity.ok(toResponse(agent));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        String owner = owner(jwt);
        service.delete(owner, id);
        return ResponseEntity.noContent().build();
    }

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "global";
        }
        return jwt.getSubject();
    }

    private static AgentResponse toResponse(UserAgent agent) {
        return new AgentResponse(
                agent.id(),
                agent.name(),
                agent.purpose(),
                agent.systemPrompt(),
                agent.model(),
                agent.createdAt(),
                agent.updatedAt());
    }
}
