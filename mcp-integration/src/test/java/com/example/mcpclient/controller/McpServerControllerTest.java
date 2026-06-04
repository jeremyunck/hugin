package com.example.mcpclient.controller;

import com.example.mcpclient.model.McpServerDefinition;
import com.example.mcpclient.model.ServerInfo;
import com.example.mcpclient.service.McpServerRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class McpServerControllerTest {

    @Mock
    private McpServerRegistryService registry;

    @InjectMocks
    private McpServerController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // -------------------------------------------------------------------------
    // GET /api/servers
    // -------------------------------------------------------------------------

    @Test
    void listServersReturnsEmptyList() throws Exception {
        when(registry.listServers()).thenReturn(List.of());
        mockMvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listServersReturnsAll() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "fs"), null, null, null);
        ServerInfo info = new ServerInfo("fs", def, true, null, List.of());
        when(registry.listServers()).thenReturn(List.of(info));

        mockMvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("fs"))
                .andExpect(jsonPath("$[0].connected").value(true));
    }

    // -------------------------------------------------------------------------
    // GET /api/servers/{name}
    // -------------------------------------------------------------------------

    @Test
    void getServerReturnsOneServer() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "fs"), null, null, null);
        ServerInfo info = new ServerInfo("fs", def, true, null, List.of());
        when(registry.getServer("fs")).thenReturn(info);

        mockMvc.perform(get("/api/servers/fs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fs"))
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    void getServerReturns404WhenNotFound() throws Exception {
        when(registry.getServer("missing")).thenThrow(new NoSuchElementException("Server not found: missing"));

        mockMvc.perform(get("/api/servers/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Server not found: missing"));
    }

    // -------------------------------------------------------------------------
    // PUT /api/servers/{name}
    // -------------------------------------------------------------------------

    @Test
    void addOrUpdateServerReturnsOk() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of("-y", "server"), Map.of(), null, null);
        ServerInfo info = new ServerInfo("fs", def, true, null, List.of());
        when(registry.addServer(eq("fs"), any())).thenReturn(info);

        mockMvc.perform(put("/api/servers/fs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(def)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fs"));
    }

    @Test
    void addOrUpdateServerForSseDefinition() throws Exception {
        McpServerDefinition def = new McpServerDefinition(null, null, null, "http://localhost:9000/sse", null);
        ServerInfo info = new ServerInfo("remote", def, false, "connection refused", null);
        when(registry.addServer(eq("remote"), any())).thenReturn(info);

        mockMvc.perform(put("/api/servers/remote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(def)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("remote"))
                .andExpect(jsonPath("$.connected").value(false));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/servers/{name}
    // -------------------------------------------------------------------------

    @Test
    void removeServerReturnsOkWithMessage() throws Exception {
        doNothing().when(registry).removeServer("fs");

        mockMvc.perform(delete("/api/servers/fs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Server 'fs' removed"));
    }

    @Test
    void removeServerReturns404WhenNotFound() throws Exception {
        doThrow(new NoSuchElementException("Server not found: ghost"))
                .when(registry).removeServer("ghost");

        mockMvc.perform(delete("/api/servers/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Server not found: ghost"));
    }

    // -------------------------------------------------------------------------
    // POST /api/servers/{name}/reconnect
    // -------------------------------------------------------------------------

    @Test
    void reconnectReturnsServerInfo() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of(), null, null, null);
        ServerInfo info = new ServerInfo("fs", def, true, null, List.of());
        when(registry.reconnect("fs")).thenReturn(info);

        mockMvc.perform(post("/api/servers/fs/reconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fs"))
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    void reconnectReturns404WhenServerUnknown() throws Exception {
        when(registry.reconnect("nope")).thenThrow(new NoSuchElementException("Server not found: nope"));

        mockMvc.perform(post("/api/servers/nope/reconnect"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // GET /api/servers/{name}/tools
    // -------------------------------------------------------------------------

    @Test
    void listToolsReturnsToolList() throws Exception {
        List<ServerInfo.ToolInfo> tools = List.of(
                new ServerInfo.ToolInfo("read_file", "Read a file"),
                new ServerInfo.ToolInfo("write_file", "Write a file")
        );
        when(registry.listTools("fs")).thenReturn(tools);

        mockMvc.perform(get("/api/servers/fs/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("read_file"))
                .andExpect(jsonPath("$[1].name").value("write_file"));
    }

    @Test
    void listToolsReturns409WhenNotConnected() throws Exception {
        when(registry.listTools("fs"))
                .thenThrow(new IllegalStateException("Server 'fs' is not connected"));

        mockMvc.perform(get("/api/servers/fs/tools"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Server 'fs' is not connected"));
    }

    // -------------------------------------------------------------------------
    // Exception handler tests
    // -------------------------------------------------------------------------

    @Test
    void handleIllegalStateExceptionReturnsConflict() throws Exception {
        McpServerDefinition def = new McpServerDefinition("npx", List.of(), null, null, null);
        when(registry.addServer(any(), any()))
                .thenThrow(new IllegalStateException("conflict situation"));

        mockMvc.perform(put("/api/servers/fs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(def)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict situation"));
    }

    @Test
    void handleIllegalArgumentExceptionReturnsBadRequest() throws Exception {
        McpServerDefinition def = new McpServerDefinition(null, null, null, null, null);
        when(registry.addServer(any(), any()))
                .thenThrow(new IllegalArgumentException("'command' is required for stdio servers"));

        mockMvc.perform(put("/api/servers/bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(def)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'command' is required for stdio servers"));
    }
}
