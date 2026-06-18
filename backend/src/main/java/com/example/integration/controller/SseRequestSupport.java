package com.example.integration.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerMapping;

final class SseRequestSupport {

    private static final String SSE_CONTENT_TYPE = MediaType.TEXT_EVENT_STREAM_VALUE;

    private SseRequestSupport() {
    }

    static boolean acceptsEventStream(HttpServletRequest request, HttpServletResponse response) {
        if (request == null) {
            return false;
        }
        boolean acceptHeaderMatches = java.util.Collections.list(request.getHeaders("Accept")).stream()
                .anyMatch(value -> value != null && value.contains(SSE_CONTENT_TYPE));
        Object produces = request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        boolean handlerProducesEventStream = produces instanceof java.util.Set<?> mediaTypes
                && mediaTypes.stream()
                .filter(MediaType.class::isInstance)
                .map(MediaType.class::cast)
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        boolean responseIsEventStream = response != null
                && response.getContentType() != null
                && response.getContentType().contains(SSE_CONTENT_TYPE);
        return acceptHeaderMatches || handlerProducesEventStream || responseIsEventStream;
    }

    static void ensureConnected(boolean sent) {
        if (!sent) {
            throw new ClientDisconnectedException();
        }
    }

    /**
     * Abort the background stream task as soon as a write shows the client has gone away.
     */
    static final class ClientDisconnectedException extends RuntimeException {
    }
}
