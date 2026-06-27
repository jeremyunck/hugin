package com.example.integration.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests session reuse, re-open on expiry, credential-change re-open, and invalidation. */
class McpSessionManagerTest {

    private McpTransports transports;
    private McpSession session;
    private McpSessionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        transports = Mockito.mock(McpTransports.class);
        session = Mockito.mock(McpSession.class);
        when(transports.openSession(any(), any())).thenReturn(session);
        manager = new McpSessionManager(transports, 300);
    }

    private McpServerEntity server() {
        Instant now = Instant.now();
        return new McpServerEntity("s1", "alice", "linear", "Linear", McpTransport.STREAMABLE_HTTP,
                "https://example.com/mcp", McpAuthType.BEARER_TOKEN, "enc", true, now, now, null);
    }

    @Test
    void reusesOneSessionAcrossCalls() throws Exception {
        when(session.callTool(any(), any())).thenReturn("ok");

        manager.callTool(server(), "tok", "do", Map.of());
        manager.callTool(server(), "tok", "do", Map.of());

        assertThat(manager).isNotNull();
        verify(transports, times(1)).openSession(any(), any()); // opened once, reused once
        verify(session, times(2)).callTool(any(), any());
    }

    @Test
    void reopensOnSessionExpiry() throws Exception {
        when(session.callTool(any(), any()))
                .thenThrow(new McpHttpClient.McpSessionExpiredException("expired"))
                .thenReturn("recovered");

        String result = manager.callTool(server(), "tok", "do", Map.of());

        assertThat(result).isEqualTo("recovered");
        verify(transports, times(2)).openSession(any(), any()); // initial + re-open after expiry
    }

    @Test
    void reopensWhenCredentialChanges() throws Exception {
        when(session.callTool(any(), any())).thenReturn("ok");

        manager.callTool(server(), "tok-1", "do", Map.of());
        manager.callTool(server(), "tok-2", "do", Map.of()); // different token → new fingerprint

        verify(transports, times(2)).openSession(any(), any());
    }

    @Test
    void invalidateClosesAndForcesReopen() throws Exception {
        when(session.callTool(any(), any())).thenReturn("ok");

        manager.callTool(server(), "tok", "do", Map.of());
        manager.invalidate("s1");
        manager.callTool(server(), "tok", "do", Map.of());

        verify(session, times(1)).close();
        verify(transports, times(2)).openSession(any(), any());
    }
}
