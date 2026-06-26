package com.example.integration.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads the remaining credit balance for a user's OpenRouter key via OpenRouter's
 * {@code GET /credits} endpoint, which returns the lifetime credits purchased and the lifetime usage.
 * The remaining balance is their difference. This backs the usage meter in the UI.
 */
@Service
public class OpenRouterCreditsService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterCreditsService.class);

    private final String apiBaseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenRouterCreditsService(
            @Value("${openrouter.api-base-url:https://openrouter.ai/api/v1}") String apiBaseUrl,
            ObjectMapper objectMapper) {
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Total credits, total usage, and the remaining balance (all in OpenRouter credit units). */
    public record Credits(double totalCredits, double totalUsage, double remaining) {}

    /** Thrown when OpenRouter rejects the key or the balance cannot be retrieved. */
    public static class CreditsException extends Exception {
        public CreditsException(String message) {
            super(message);
        }
    }

    /**
     * Fetches the credit balance for {@code apiKey}.
     *
     * @throws CreditsException on an authentication failure, API error, or malformed response
     */
    public Credits fetch(String apiKey) throws CreditsException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/credits"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new CreditsException("Could not reach OpenRouter: " + e.getMessage());
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new CreditsException("OpenRouter rejected the API key (HTTP " + status + ")");
        }
        if (status != 200) {
            log.warn("OpenRouter credits request returned {}: {}", status, response.body());
            throw new CreditsException("OpenRouter API error " + status);
        }

        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            double totalCredits = data.path("total_credits").asDouble(0);
            double totalUsage = data.path("total_usage").asDouble(0);
            return new Credits(totalCredits, totalUsage, totalCredits - totalUsage);
        } catch (Exception e) {
            throw new CreditsException("Unexpected response from OpenRouter");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
