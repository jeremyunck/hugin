package com.example.integration.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * User-facing REST API for managing MCP server connections and their discovered tools.
 *
 * <p>The owner is ALWAYS derived from the authenticated JWT and never accepted from a request body, so
 * a user can only ever see and mutate their own servers. Secrets (bearer tokens, OAuth tokens, client
 * secrets) are write-only: accepted on create/update but never returned.
 *
 * <p>The single exception to JWT auth is {@code GET /oauth/callback}, which the external authorization
 * server redirects the browser to; it carries no session and is pinned to a user by its opaque
 * {@code state} (see {@link McpOAuthService}). It is allow-listed in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    public record ToolToggleRequest(Boolean enabled) {}

    public record OAuthStartResponse(String authorizationUrl) {}

    private final McpConnectionService connectionService;
    private final McpDiscoveryService discoveryService;
    private final McpOAuthService oauthService;
    private final McpCatalogService catalogService;
    private final String redirectBaseUrl;

    public McpController(McpConnectionService connectionService,
                         McpDiscoveryService discoveryService,
                         McpOAuthService oauthService,
                         McpCatalogService catalogService,
                         @Value("${mcp.oauth.redirect-base-url:}") String redirectBaseUrl) {
        this.connectionService = connectionService;
        this.discoveryService = discoveryService;
        this.oauthService = oauthService;
        this.catalogService = catalogService;
        this.redirectBaseUrl = redirectBaseUrl;
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

    /** Curated catalog of well-known MCP servers for one-click setup. */
    @GetMapping("/catalog")
    public List<McpCatalogEntry> catalog() {
        return catalogService.catalog();
    }

    /** Begins the OAuth Authorization Code flow; returns the URL the browser should open. */
    @PostMapping("/servers/{id}/oauth/start")
    public OAuthStartResponse startOAuth(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        McpServerEntity server = connectionService.require(owner(jwt), id);
        String url = oauthService.startAuthorization(server, callbackUrl());
        return new OAuthStartResponse(url);
    }

    /**
     * OAuth redirect target. Public (no JWT): the request comes straight from the authorization server's
     * browser redirect and is authenticated by its opaque {@code state}. Returns a small HTML page.
     */
    @GetMapping("/oauth/callback")
    public ResponseEntity<String> oauthCallback(@RequestParam(required = false) String code,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) String error) {
        McpOAuthService.CallbackResult result = oauthService.handleCallback(state, code, error);
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(callbackHtml(result));
    }

    private String callbackUrl() {
        if (redirectBaseUrl != null && !redirectBaseUrl.isBlank()) {
            String base = redirectBaseUrl.endsWith("/")
                    ? redirectBaseUrl.substring(0, redirectBaseUrl.length() - 1) : redirectBaseUrl;
            return base + "/api/mcp/oauth/callback";
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/mcp/oauth/callback")
                .build()
                .toUriString();
    }

    private static String callbackHtml(McpOAuthService.CallbackResult result) {
        String heading = result.success() ? "Connected" : "Connection failed";
        String safeMessage = result.message() == null ? "" : result.message()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + heading + "</title></head>"
                + "<body style=\"font-family:system-ui,sans-serif;padding:2rem;text-align:center\">"
                + "<h2>" + heading + "</h2><p>" + safeMessage + "</p>"
                + "<p>You can close this window and return to Hugin.</p>"
                + "<script>setTimeout(function(){window.close();},1500);</script>"
                + "</body></html>";
    }

    private static String owner(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "global";
        }
        return jwt.getSubject();
    }
}
