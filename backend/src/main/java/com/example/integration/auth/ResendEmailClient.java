package com.example.integration.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal transport for sending transactional email through <a href="https://resend.com">Resend</a>.
 *
 * <p>Used by {@link EmailVerificationService} to deliver the 6-digit login/registration codes. The
 * client is "configured" only when both an API key and a verified {@code from} address are present;
 * when it is not configured the verification service falls back to logging the code (local dev),
 * so the flow remains testable without a real Resend account.
 */
@Component
public class ResendEmailClient {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailClient.class);

    private final String apiKey;
    private final String fromAddress;
    private final String endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public ResendEmailClient(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:onboarding@resend.dev}") String fromAddress,
            @Value("${resend.endpoint:https://api.resend.com/emails}") String endpoint,
            ObjectMapper objectMapper) {
        this(apiKey, fromAddress, endpoint, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    ResendEmailClient(String apiKey, String fromAddress, String endpoint, ObjectMapper objectMapper,
                      HttpClient httpClient) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Thrown when Resend rejects the request or the network call fails. */
    public static class EmailException extends RuntimeException {
        public EmailException(String message) {
            super(message);
        }

        public EmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Whether a usable Resend configuration (API key + verified sender) is present. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && fromAddress != null && !fromAddress.isBlank();
    }

    /**
     * Sends a single HTML email. Callers should guard with {@link #isConfigured()} first; calling
     * this while unconfigured throws.
     */
    public void send(String to, String subject, String html) {
        if (!isConfigured()) {
            throw new EmailException("Resend is not configured (set RESEND_API_KEY and RESEND_FROM)");
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "from", fromAddress,
                    "to", List.of(to),
                    "subject", subject,
                    "html", html));
        } catch (IOException e) {
            throw new EmailException("Failed to serialize email request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return;
            }
            log.warn("Resend API returned {}: {}", status, response.body());
            throw new EmailException("Resend API error " + status);
        } catch (IOException e) {
            throw new EmailException("Failed to reach Resend", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailException("Interrupted while sending email", e);
        }
    }
}
