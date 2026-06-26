package com.example.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and validates the short-lived 6-digit codes that gate both registration and login.
 *
 * <p>A challenge is keyed by the (normalized) email and carries everything needed to finish the flow
 * once the code is confirmed: the {@link Purpose} and, for a pending registration, the already-hashed
 * password. Challenges live in memory only — they are intentionally ephemeral (a few minutes) so
 * losing them on a restart simply means the user requests a new code. The code itself is delivered
 * via {@link ResendEmailClient}; when Resend is not configured the code is logged instead so the
 * flow stays usable in local development.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Purpose {
        REGISTER,
        LOGIN
    }

    /** A pending verification. {@code passwordHash} is set only for {@link Purpose#REGISTER}. */
    public static final class Challenge {
        private final Purpose purpose;
        private final String code;
        private final String passwordHash;
        private final Instant expiresAt;
        private int attempts;

        Challenge(Purpose purpose, String code, String passwordHash, Instant expiresAt) {
            this.purpose = purpose;
            this.code = code;
            this.passwordHash = passwordHash;
            this.expiresAt = expiresAt;
        }

        public Purpose purpose() {
            return purpose;
        }

        public String passwordHash() {
            return passwordHash;
        }
    }

    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final ResendEmailClient emailClient;
    private final Clock clock;
    private final Duration codeTtl;
    private final int maxAttempts;

    public EmailVerificationService(
            ResendEmailClient emailClient,
            Clock clock,
            @Value("${auth.email-verification.code-ttl:10m}") Duration codeTtl,
            @Value("${auth.email-verification.max-attempts:5}") int maxAttempts) {
        this.emailClient = emailClient;
        this.clock = clock;
        this.codeTtl = codeTtl;
        this.maxAttempts = maxAttempts;
    }

    /** Creates a fresh challenge for {@code email}, replacing any prior one, and delivers the code. */
    public void startChallenge(String email, Purpose purpose, String passwordHash) {
        String code = generateCode();
        Instant expiresAt = clock.instant().plus(codeTtl);
        challenges.put(normalize(email), new Challenge(purpose, code, passwordHash, expiresAt));
        deliver(email, code);
    }

    /**
     * Validates {@code code} against the pending challenge for {@code email} and consumes it on
     * success. Throws a 4xx {@link ResponseStatusException} when there is no challenge, it has
     * expired, the attempt budget is exhausted, or the code does not match.
     */
    public Challenge verify(String email, String code) {
        String key = normalize(email);
        Challenge challenge = challenges.get(key);
        if (challenge == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No verification in progress. Please request a new code.");
        }
        if (clock.instant().isAfter(challenge.expiresAt)) {
            challenges.remove(key);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Verification code has expired. Please request a new code.");
        }
        challenge.attempts++;
        if (challenge.attempts > maxAttempts) {
            challenges.remove(key);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many incorrect attempts. Please request a new code.");
        }
        String submitted = code == null ? "" : code.trim();
        if (!constantTimeEquals(challenge.code, submitted)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification code.");
        }
        challenges.remove(key);
        return challenge;
    }

    private void deliver(String email, String code) {
        if (!emailClient.isConfigured()) {
            log.warn("Resend is not configured (set RESEND_API_KEY/RESEND_FROM). "
                    + "Verification code for {} is {} — DEV FALLBACK, do not rely on this in production.",
                    email, code);
            return;
        }
        emailClient.send(email, "Your Hugin verification code", buildHtml(code));
    }

    private static String buildHtml(String code) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#1c1f23\">"
                + "<p>Your Hugin verification code is:</p>"
                + "<p style=\"font-size:32px;font-weight:700;letter-spacing:6px\">" + code + "</p>"
                + "<p style=\"color:#8b9099;font-size:13px\">This code expires shortly. "
                + "If you did not request it, you can ignore this email.</p>"
                + "</div>";
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expected.length(); i++) {
            result |= expected.charAt(i) ^ actual.charAt(i);
        }
        return result == 0;
    }
}
