package com.example.integration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates requests via the {@code X-API-Key} header.
 *
 * <p>Compares the header value against the configured API key. If it matches,
 * the request is authenticated with a synthetic {@link ApiKeyAuthenticationToken}.
 * Requests without the header (or with a wrong key) receive a 401 response.
 */
public class ApiKeyAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final RequestMatcher REQUIRES_KEY = request ->
            request.getServletPath() != null
                    && request.getServletPath().startsWith("/api/v1");

    private final String expectedApiKey;

    public ApiKeyAuthenticationFilter(String expectedApiKey) {
        super(REQUIRES_KEY);
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || !apiKey.equals(expectedApiKey)) {
            throw new BadCredentialsException("Invalid or missing X-API-Key header");
        }
        return new ApiKeyAuthenticationToken(apiKey, List.of(new SimpleGrantedAuthority("ROLE_API")));
    }

    @Override
    protected void successfulAuthentication(
            HttpServletRequest request, HttpServletResponse response,
            FilterChain chain, Authentication authResult) throws IOException, ServletException {
        SecurityContextHolder.getContext().setAuthentication(authResult);
        chain.doFilter(request, response);
    }
}