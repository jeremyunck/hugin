package com.example.integration.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user OpenRouter API key management and the credit-balance lookup that powers the usage meter.
 * Every endpoint is scoped to the authenticated principal ({@code jwt.getSubject()}), so a user can
 * only ever read, set, or spend against their own key — never another user's.
 */
@RestController
@RequestMapping("/api/user")
public class UserOpenRouterController {

    private final UserOpenRouterKeyService keyService;
    private final OpenRouterCreditsService creditsService;

    public UserOpenRouterController(UserOpenRouterKeyService keyService,
                                    OpenRouterCreditsService creditsService) {
        this.keyService = keyService;
        this.creditsService = creditsService;
    }

    /** Whether the user has a key on file, plus a masked suffix for display. Never returns the key. */
    @GetMapping("/openrouter-key")
    public OpenRouterKeyStatus getKeyStatus(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getSubject();
        boolean configured = keyService.hasApiKey(username);
        String last4 = configured ? keyService.maskedSuffix(username).orElse(null) : null;
        return new OpenRouterKeyStatus(configured, last4);
    }

    @PutMapping("/openrouter-key")
    public OpenRouterKeyStatus saveKey(@AuthenticationPrincipal Jwt jwt,
                                       @RequestBody SaveKeyRequest request) {
        String username = jwt.getSubject();
        if (request == null || request.apiKey() == null || request.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key is required");
        }
        try {
            keyService.saveApiKey(username, request.apiKey());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return getKeyStatus(jwt);
    }

    @DeleteMapping("/openrouter-key")
    public OpenRouterKeyStatus deleteKey(@AuthenticationPrincipal Jwt jwt) {
        keyService.clearApiKey(jwt.getSubject());
        return new OpenRouterKeyStatus(false, null);
    }

    /** Remaining credit balance for the user's key. Returns {@code configured=false} when unset. */
    @GetMapping("/openrouter-credits")
    public OpenRouterCreditsResponse getCredits(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getSubject();
        String apiKey = keyService.resolveApiKey(username).orElse(null);
        if (apiKey == null) {
            return OpenRouterCreditsResponse.notConfigured();
        }
        try {
            OpenRouterCreditsService.Credits credits = creditsService.fetch(apiKey);
            return new OpenRouterCreditsResponse(
                    true, credits.totalCredits(), credits.totalUsage(), credits.remaining(), null);
        } catch (OpenRouterCreditsService.CreditsException e) {
            return new OpenRouterCreditsResponse(true, null, null, null, e.getMessage());
        }
    }

    public record OpenRouterKeyStatus(boolean configured, String last4) {}

    public record SaveKeyRequest(String apiKey) {}

    public record OpenRouterCreditsResponse(
            boolean configured,
            Double totalCredits,
            Double totalUsage,
            Double remaining,
            String error) {

        static OpenRouterCreditsResponse notConfigured() {
            return new OpenRouterCreditsResponse(false, null, null, null, null);
        }
    }
}
