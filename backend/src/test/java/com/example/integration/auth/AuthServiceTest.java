package com.example.integration.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
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

class AuthServiceTest {

    private UserAccountRepository repository;
    private EmailVerificationService verificationService;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        repository = mock(UserAccountRepository.class);
        verificationService = mock(EmailVerificationService.class);
        passwordEncoder = new BCryptPasswordEncoder();

        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) i;
        }
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(keyBytes, "HmacSHA256")));
        AuthJwtProperties jwtProperties = new AuthJwtProperties(null, "hugin", Duration.ofHours(12));

        authService = new AuthService(repository, verificationService, passwordEncoder, jwtEncoder, jwtProperties);
    }

    @Test
    void registerStartsChallengeWithHashedPassword() {
        when(repository.findByUsername("new@example.com")).thenReturn(Optional.empty());

        AuthChallengeResponse response = authService.register(
                new AuthRegisterRequest("New@Example.com", "supersecret", "supersecret"));

        assertThat(response.verificationRequired()).isTrue();
        assertThat(response.email()).isEqualTo("new@example.com");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(verificationService).startChallenge(eq("new@example.com"),
                eq(EmailVerificationService.Purpose.REGISTER), hash.capture());
        assertThat(passwordEncoder.matches("supersecret", hash.getValue())).isTrue();
    }

    @Test
    void registerRejectsMismatchedPasswords() {
        assertThatThrownBy(() -> authService.register(
                new AuthRegisterRequest("a@b.com", "supersecret", "different1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Passwords do not match");
        verify(verificationService, never()).startChallenge(any(), any(), any());
    }

    @Test
    void registerRejectsShortPassword() {
        assertThatThrownBy(() -> authService.register(
                new AuthRegisterRequest("a@b.com", "short", "short")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least 8");
    }

    @Test
    void registerRejectsInvalidEmail() {
        assertThatThrownBy(() -> authService.register(
                new AuthRegisterRequest("not-an-email", "supersecret", "supersecret")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("valid email");
    }

    @Test
    void registerRejectsExistingEmail() {
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.of(new UserAccount("a@b.com", "hash", true, List.of("ROLE_USER"))));

        assertThatThrownBy(() -> authService.register(
                new AuthRegisterRequest("a@b.com", "supersecret", "supersecret")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void loginStartsChallengeWhenPasswordMatches() {
        String hash = passwordEncoder.encode("supersecret");
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.of(new UserAccount("a@b.com", hash, true, List.of("ROLE_USER"))));

        AuthChallengeResponse response = authService.login(new AuthLoginRequest("A@B.com", "supersecret"));

        assertThat(response.verificationRequired()).isTrue();
        verify(verificationService).startChallenge("a@b.com", EmailVerificationService.Purpose.LOGIN, null);
    }

    @Test
    void loginRejectsWrongPassword() {
        String hash = passwordEncoder.encode("supersecret");
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.of(new UserAccount("a@b.com", hash, true, List.of("ROLE_USER"))));

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("a@b.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);
        verify(verificationService, never()).startChallenge(any(), any(), any());
    }

    @Test
    void loginRejectsUnknownUser() {
        when(repository.findByUsername("a@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("a@b.com", "supersecret")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void verifyCreatesRoleUserAccountForRegistration() {
        when(verificationService.verify("a@b.com", "123456"))
                .thenReturn(new EmailVerificationService.Challenge(
                        EmailVerificationService.Purpose.REGISTER, "123456", "pw-hash", null));
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new UserAccount("a@b.com", "pw-hash", true, List.of("ROLE_USER"))));

        AuthLoginResponse response = authService.verify(new AuthVerifyRequest("A@B.com", "123456"));

        verify(repository).createUser("a@b.com", "pw-hash");
        assertThat(response.username()).isEqualTo("a@b.com");
        assertThat(response.roles()).containsExactly("ROLE_USER");
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void verifyIssuesTokenForLoginWithoutCreatingUser() {
        when(verificationService.verify("a@b.com", "654321"))
                .thenReturn(new EmailVerificationService.Challenge(
                        EmailVerificationService.Purpose.LOGIN, "654321", null, null));
        when(repository.findByUsername("a@b.com"))
                .thenReturn(Optional.of(new UserAccount("a@b.com", "hash", true, List.of("ROLE_USER"))));

        AuthLoginResponse response = authService.verify(new AuthVerifyRequest("a@b.com", "654321"));

        verify(repository, never()).createUser(any(), any());
        assertThat(response.token()).isNotBlank();
        assertThat(response.username()).isEqualTo("a@b.com");
    }
}
