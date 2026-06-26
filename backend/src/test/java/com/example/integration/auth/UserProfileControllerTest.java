package com.example.integration.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileControllerTest {

    private UserAccountRepository repository;
    private PasswordEncoder passwordEncoder;
    private EmailVerificationService verificationService;
    private UserProfileController controller;

    private static Jwt jwtFor(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @BeforeEach
    void setUp() {
        repository = mock(UserAccountRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        verificationService = mock(EmailVerificationService.class);
        controller = new UserProfileController(repository, passwordEncoder, verificationService);
    }

    @Test
    void requestPasswordResetStartsChallengeWithHashedNewPassword() {
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.of(new UserAccount("a@b.com", "old-hash", true, List.of("ROLE_USER"))));

        AuthChallengeResponse response = controller.requestPasswordReset(
                jwtFor("a@b.com"),
                new UserProfileController.ResetPasswordRequest("brand-new-password"));

        assertThat(response.verificationRequired()).isTrue();
        assertThat(response.email()).isEqualTo("a@b.com");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(verificationService).startChallenge(eq("a@b.com"),
                eq(EmailVerificationService.Purpose.PASSWORD_RESET), hash.capture());
        assertThat(passwordEncoder.matches("brand-new-password", hash.getValue())).isTrue();
        // The new password must not be persisted until the code is confirmed.
        verify(repository, never()).updatePassword(any(), any());
    }

    @Test
    void requestPasswordResetRejectsShortPassword() {
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.of(new UserAccount("a@b.com", "old-hash", true, List.of("ROLE_USER"))));

        assertThatThrownBy(() -> controller.requestPasswordReset(
                jwtFor("a@b.com"),
                new UserProfileController.ResetPasswordRequest("short")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least 8");
        verify(verificationService, never()).startChallenge(any(), any(), any());
    }

    @Test
    void confirmPasswordResetPersistsTheVerifiedPassword() {
        when(verificationService.verify("a@b.com", "123456"))
                .thenReturn(new EmailVerificationService.Challenge(
                        EmailVerificationService.Purpose.PASSWORD_RESET, "123456", "new-hash", null));

        controller.confirmPasswordReset(jwtFor("a@b.com"),
                new UserProfileController.ConfirmResetRequest("123456"));

        verify(repository).updatePassword("a@b.com", "new-hash");
    }

    @Test
    void confirmPasswordResetRejectsNonResetChallenge() {
        when(verificationService.verify("a@b.com", "123456"))
                .thenReturn(new EmailVerificationService.Challenge(
                        EmailVerificationService.Purpose.LOGIN, "123456", null, null));

        assertThatThrownBy(() -> controller.confirmPasswordReset(jwtFor("a@b.com"),
                new UserProfileController.ConfirmResetRequest("123456")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No password reset in progress");
        verify(repository, never()).updatePassword(any(), any());
    }
}
