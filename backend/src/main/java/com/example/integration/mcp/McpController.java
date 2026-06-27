package com.example.integration.mcp;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User-facing REST API for managing MCP server connections and their discovered tools.
 *
 * <p>The owner is ALWAYS derived from the authenticated JWT and never accepted from a request body, so
 * a user can only ever see and mutate their own servers. Bearer tokens are write-only: they are
 * accepted on create/update but never returned (responses report only {@code hasToken}).
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    public record ToolToggleRequest(Boolean enabled) {}

    private final McpConnectionService connectionService;
    private final McpDiscoveryService discoveryService;

    public McpController(McpConnectionService connectionService, McpDiscoveryService discoveryService) {
        this.connectionService = connectionService;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/servers")
    public List<McpServerDto> listServers(@AuthenticationPrincipal Jwt jwt) {
        return connectionService.listDtos(owner(jwt));
    }

    @PostMapping("/servers")
    public ResponseEntity<McpServerDto> createServer(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestBody McpCreateRequest request) {
        McpServerEntity server = connectionService.create(owner(jwt), request);
        return ResponseEntity.ok(connectionService.toDto(server));
    }

    @GetMapping("/servers/{id}")
    public McpServerDto getServer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return connectionService.toDto(connectionService.require(owner(jwt), id));
    }

    @PatchMapping("/servers/{id}")
    public McpServerDto updateServer(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable String id,
                                     @RequestBody McpUpdateRequest request) {
        return connectionService.toDto(connectionService.update(owner(jwt), id, request));
    }

    @DeleteMapping("/servers/{id}")
    public ResponseEntity<Void> deleteServer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        connectionService.delete(owner(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/servers/{id}/test")
    public McpTestResponse testServer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return connectionService.test(owner(jwt), id);
    }

    @PostMapping("/servers/{id}/discover")
    public McpDiscoveryResponse discoverTools(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        McpServerEntity server = connectionService.require(owner(jwt), id);
        return discoveryService.discover(server);
    }

    @PatchMapping("/servers/{serverId}/tools/{toolId}")
    public McpToolDto updateTool(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable String serverId,
                                 @PathVariable String toolId,
                                 @RequestBody ToolToggleRequest request) {
        boolean enabled = request.enabled() != null && request.enabled();
        return connectionService.setToolEnabled(owner(jwt), serverId, toolId, enabled);
    }

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "global";
        }
        return jwt.getSubject();
    }
}
