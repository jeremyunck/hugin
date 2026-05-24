package com.example.mcpclient.controller;

import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.ServerInfo;
import com.example.mcpclient.service.McpServerRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/servers")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final McpServerRegistryService registry;

    public McpServerController(McpServerRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ServerInfo> listServers() {
        log.debug("GET /api/servers");
        List<ServerInfo> servers = registry.listServers();
        log.debug("GET /api/servers response: {} server(s)", servers.size());
        return servers;
    }

    @GetMapping("/{name}")
    public ServerInfo getServer(@PathVariable String name) {
        log.debug("GET /api/servers/{}", name);
        ServerInfo info = registry.getServer(name);
        log.debug("GET /api/servers/{} response: connected={}", name, info.connected());
        return info;
    }

    @PutMapping("/{name}")
    public ResponseEntity<ServerInfo> addOrUpdateServer(
            @PathVariable String name,
            @RequestBody McpServerDefinition definition) {
        log.debug("PUT /api/servers/{} type={}", name, definition.resolvedType());
        ServerInfo info = registry.addServer(name, definition);
        log.debug("PUT /api/servers/{} response: connected={}", name, info.connected());
        return ResponseEntity.ok(info);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, String>> removeServer(@PathVariable String name) {
        log.debug("DELETE /api/servers/{}", name);
        registry.removeServer(name);
        return ResponseEntity.ok(Map.of("message", "Server '" + name + "' removed"));
    }

    @PostMapping("/{name}/reconnect")
    public ServerInfo reconnect(@PathVariable String name) {
        log.debug("POST /api/servers/{}/reconnect", name);
        ServerInfo info = registry.reconnect(name);
        log.debug("POST /api/servers/{}/reconnect response: connected={}", name, info.connected());
        return info;
    }

    @GetMapping("/{name}/tools")
    public List<ServerInfo.ToolInfo> listTools(@PathVariable String name) {
        log.debug("GET /api/servers/{}/tools", name);
        List<ServerInfo.ToolInfo> tools = registry.listTools(name);
        log.debug("GET /api/servers/{}/tools response: {} tool(s)", name, tools.size());
        return tools;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        log.debug("Not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        log.debug("Bad state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
}
