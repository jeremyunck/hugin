package com.example.integration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);

        // SSE responses are written asynchronously on a background thread, after the filter chain
        // has already returned. Wrapping them with ContentCachingResponseWrapper would buffer the
        // async events and prevent them from reaching the client. Detect SSE requests upfront via
        // the Accept header and bypass response caching entirely for those requests.
        boolean isSse = MediaType.TEXT_EVENT_STREAM_VALUE.equals(request.getHeader(HttpHeaders.ACCEPT));
        if (isSse) {
            filterChain.doFilter(wrappedRequest, response);
            logRequest(wrappedRequest);
            log.debug("? {} {} status={}", request.getMethod(), request.getRequestURI(), response.getStatus());
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            logRequest(wrappedRequest);
            logResponse(request.getMethod(), request.getRequestURI(), wrappedResponse);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        if (!log.isDebugEnabled()) return;
        byte[] body = request.getContentAsByteArray();
        if (body.length > 0) {
            log.debug("→ {} {} body={}",
                    request.getMethod(), request.getRequestURI(),
                    new String(body, StandardCharsets.UTF_8));
        } else {
            log.debug("→ {} {}", request.getMethod(), request.getRequestURI());
        }
    }

    private void logResponse(String method, String uri, ContentCachingResponseWrapper response) {
        if (!log.isDebugEnabled()) return;
        byte[] body = response.getContentAsByteArray();
        if (body.length > 0) {
            log.debug("← {} {} status={} body={}",
                    method, uri, response.getStatus(),
                    new String(body, StandardCharsets.UTF_8));
        } else {
            log.debug("← {} {} status={}", method, uri, response.getStatus());
        }
    }
}
