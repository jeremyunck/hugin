package com.example.integration.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

    /** Captures the emitted code by stubbing the email client to record what it would send. */
    private static final class CapturingClient extends ResendEmailClient {
        String lastHtml;
        private final boolean configured;

        CapturingClient(boolean configured) {
            super("", "", "https://example.invalid", null, null);
            this.configured = configured;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void send(String to, String subject, String html) {
            this.lastHtml = html;
        }
    }

    private static String codeFrom(String html) {
        // The code is the 6-digit run rendered in the email body.
        return html.replaceAll("(?s).*?(\\d{6}).*", "$1");
    }

    @Test
    void verifiesCorrectCodeAndReturnsChallenge() {
        CapturingClient client = new CapturingClient(true);
        EmailVerificationService service = new EmailVerificationService(
                client, Clock.systemUTC(), Duration.ofMinutes(10), 5);

        service.startChallenge("User@Example.com", EmailVerificationService.Purpose.REGISTER, "hash");
        String code = codeFrom(client.lastHtml);

        EmailVerificationService.Challenge challenge = service.verify("user@example.com", code);
        assertThat(challenge.purpose()).isEqualTo(EmailVerificationService.Purpose.REGISTER);
        assertThat(challenge.passwordHash()).isEqualTo("hash");
    }

    @Test
    void rejectsUnknownEmail() {
        EmailVerificationService service = new EmailVerificationService(
                new CapturingClient(true), Clock.systemUTC(), Duration.ofMinutes(10), 5);

        assertThatThrownBy(() -> service.verify("nobody@example.com", "123456"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No verification in progress");
    }

    @Test
    void rejectsWrongCodeThenSucceedsWithCorrectOne() {
        CapturingClient client = new CapturingClient(true);
        EmailVerificationService service = new EmailVerificationService(
                client, Clock.systemUTC(), Duration.ofMinutes(10), 5);
        service.startChallenge("a@b.com", EmailVerificationService.Purpose.LOGIN, null);
        String code = codeFrom(client.lastHtml);

        assertThatThrownBy(() -> service.verify("a@b.com", "000000"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid verification code");

        // A wrong attempt does not consume the challenge.
        assertThat(service.verify("a@b.com", code)).isNotNull();
    }

    @Test
    void rejectsExpiredCode() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        CapturingClient client = new CapturingClient(true);
        EmailVerificationService service = new EmailVerificationService(
                client, clock, Duration.ofMinutes(10), 5);
        service.startChallenge("a@b.com", EmailVerificationService.Purpose.LOGIN, null);
        String code = codeFrom(client.lastHtml);

        now.set(now.get().plus(Duration.ofMinutes(11)));

        assertThatThrownBy(() -> service.verify("a@b.com", code))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void invalidatesAfterTooManyAttempts() {
        CapturingClient client = new CapturingClient(true);
        EmailVerificationService service = new EmailVerificationService(
                client, Clock.systemUTC(), Duration.ofMinutes(10), 3);
        service.startChallenge("a@b.com", EmailVerificationService.Purpose.LOGIN, null);
        String code = codeFrom(client.lastHtml);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.verify("a@b.com", "000000"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        // The 4th attempt — even with the correct code — is rejected and the challenge is gone.
        assertThatThrownBy(() -> service.verify("a@b.com", code))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Too many");
    }

    @Test
    void logsCodeWhenEmailClientNotConfigured() {
        CapturingClient client = new CapturingClient(false);
        EmailVerificationService service = new EmailVerificationService(
                client, Clock.systemUTC(), Duration.ofMinutes(10), 5);

        // Should not throw even though no email is actually sent (dev fallback path).
        service.startChallenge("a@b.com", EmailVerificationService.Purpose.LOGIN, null);
        assertThat(client.lastHtml).isNull();
    }
}
