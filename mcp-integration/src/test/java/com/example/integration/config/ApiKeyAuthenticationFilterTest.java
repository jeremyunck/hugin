package com.example.integration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthenticationFilterTest {

    private static final String API_KEY = "secret-key";

    private ApiKeyAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(API_KEY);
        request = new MockHttpServletRequest("POST", "/api/v1/agent/chat");
        request.setServletPath("/api/v1/agent/chat");
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validApiKeyAuthenticatesRequest() {
        request.addHeader("X-API-Key", API_KEY);

        Authentication auth = filter.attemptAuthentication(request, response);

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(API_KEY);
    }

    @Test
    void missingApiKeyThrowsBadCredentials() {
        // No X-API-Key header set
        assertThatThrownBy(() -> filter.attemptAuthentication(request, response))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("X-API-Key");
    }

    @Test
    void wrongApiKeyThrowsBadCredentials() {
        request.addHeader("X-API-Key", "wrong-key");

        assertThatThrownBy(() -> filter.attemptAuthentication(request, response))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void emptyApiKeyThrowsBadCredentials() {
        request.addHeader("X-API-Key", "");

        assertThatThrownBy(() -> filter.attemptAuthentication(request, response))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void successfulAuthenticationSetsSecurityContextAndContinuesChain() throws IOException, ServletException {
        request.addHeader("X-API-Key", API_KEY);
        Authentication auth = filter.attemptAuthentication(request, response);

        MockFilterChain filterChain = new MockFilterChain();
        filter.successfulAuthentication(request, response, filterChain, auth);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(API_KEY);
        // Verify the filter chain was called (MockFilterChain records the last request/response)
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void validApiKeyHasRoleApiAuthority() {
        request.addHeader("X-API-Key", API_KEY);

        Authentication auth = filter.attemptAuthentication(request, response);

        assertThat(auth.getAuthorities())
                .hasSize(1)
                .anyMatch(a -> a.getAuthority().equals("ROLE_API"));
    }
}
