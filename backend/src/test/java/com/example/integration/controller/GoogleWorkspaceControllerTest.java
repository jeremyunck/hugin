package com.example.integration.controller;

import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleReconnectResponse;
import com.example.integration.google.GoogleWorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GoogleWorkspaceControllerTest {

    @Mock
    private GoogleWorkspaceClientFactory google;

    @InjectMocks
    private GoogleWorkspaceController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusReturnsGoogleWorkspaceState() throws Exception {
        when(google.status()).thenReturn(new GoogleWorkspaceStatus(
                true,
                true,
                true,
                "oauth",
                "Google OAuth is connected."));

        mockMvc.perform(get("/api/google/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.authMode").value("oauth"))
                .andExpect(jsonPath("$.message").value("Google OAuth is connected."));
    }

    @Test
    void reconnectReturnsLatestStatus() throws Exception {
        when(google.beginReconnect(null)).thenReturn(new GoogleReconnectResponse(
                new GoogleWorkspaceStatus(
                true,
                true,
                true,
                "oauth",
                "Google OAuth is connected."),
                "https://accounts.google.com/o/oauth2/v2/auth?test"));

        mockMvc.perform(post("/api/google/reconnect")
                        .contentType("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.active").value(true))
                .andExpect(jsonPath("$.status.configured").value(true))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/o/oauth2/v2/auth?test"));
    }

    @Test
    void reconnectUsesReturnToFromRequestBody() throws Exception {
        when(google.beginReconnect("http://localhost:5173/dashboard"))
                .thenReturn(new GoogleReconnectResponse(
                        new GoogleWorkspaceStatus(true, true, true, "oauth", "Google OAuth is connected."),
                        "https://accounts.google.com/o/oauth2/v2/auth?test"));

        mockMvc.perform(post("/api/google/reconnect")
                        .contentType("application/json")
                        .content("{\"returnTo\":\"http://localhost:5173/dashboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/o/oauth2/v2/auth?test"));
    }
}
