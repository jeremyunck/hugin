package com.example.mcpclient.controller;

import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.ServerInfo;
import com.example.mcpclient.service.McpServerRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/servers")
public class McpServerController {

    private final McpServerRegistryService registry;

    public McpServerController(McpServerRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ServerInfo> listServers() {
        return registry.listServers();
    }

    @GetMapping("/{name}")
    public ServerInfo getServer(@PathVariable String name) {
        return registry.getServer(name);
    }

    @PutMapping("/{name}")
    public ResponseEntity<ServerInfo> addOrUpdateServer(
            @PathVariable String name,
            @RequestBody McpServerDefinition definition) {
        return ResponseEntity.ok(registry.addServer(name, definition));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, String>> removeServer(@PathVariable String name) {
        registry.removeServer(name);
        return ResponseEntity.ok(Map.of("message", "Server '" + name + "' removed"));
    }

    @PostMapping("/{name}/reconnect")
    public ServerInfo reconnect(@PathVariable String name) {
        return registry.reconnect(name);
    }

    @GetMapping("/{name}/tools")
    public List<ServerInfo.ToolInfo> listTools(@PathVariable String name) {
        return registry.listTools(name);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
}
