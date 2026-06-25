package com.example.integration.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultUserBootstrapTest {

    @Test
    void seedsConfiguredAndScreenshotUsersWhenBootstrapPasswordPresent() throws Exception {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("main-pass")).thenReturn("main-hash");
        when(encoder.encode("shot-pass")).thenReturn("shot-hash");

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "main-user",
                "main-pass",
                "shot-user",
                "shot-pass",
                false);

        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        verify(repository).saveOrUpdate(new UserAccount("main-user", "main-hash", true, java.util.List.of("ROLE_USER")));
        verify(repository).saveOrUpdate(new UserAccount("shot-user", "shot-hash", true, java.util.List.of("ROLE_USER")));
    }

    @Test
    void preservesExistingPrimaryUserButStillSeedsScreenshotUserWhenPrimaryPasswordBlank() throws Exception {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.findByUsername("main-user"))
                .thenReturn(Optional.of(new UserAccount("main-user", "existing", true, java.util.List.of("ROLE_USER"))));
        when(encoder.encode("shot-pass")).thenReturn("shot-hash");

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "main-user",
                "",
                "shot-user",
                "shot-pass",
                false);

        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        verify(repository, never()).saveOrUpdate(new UserAccount("main-user", "existing", true, java.util.List.of("ROLE_USER")));
        verify(repository).saveOrUpdate(new UserAccount("shot-user", "shot-hash", true, java.util.List.of("ROLE_USER")));
    }

    @Test
    void skipsScreenshotUserWhenScreenshotPasswordBlank() throws Exception {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("main-pass")).thenReturn("main-hash");

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "main-user",
                "main-pass",
                "shot-user",
                "",
                false);

        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        verify(repository).saveOrUpdate(new UserAccount("main-user", "main-hash", true, java.util.List.of("ROLE_USER")));
        verify(repository, never()).saveOrUpdate(new UserAccount("shot-user", null, true, java.util.List.of("ROLE_USER")));
        verify(encoder, never()).encode("");
    }

    @Test
    void requiresPrimaryPasswordWhenPrimaryUserMissing() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.findByUsername("main-user")).thenReturn(Optional.empty());

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "main-user",
                "",
                "shot-user",
                "shot-pass",
                false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        verify(repository, never()).saveOrUpdate(any());
    }

    @Test
    void refusesWellKnownDefaultPasswordWhenInsecureNotAllowed() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "admin",
                "change-me",
                "shot-user",
                "",
                false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        verify(repository, never()).saveOrUpdate(any());
    }

    @Test
    void refusesWellKnownDefaultPasswordCaseInsensitively() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "admin",
                "Change-Me",
                "shot-user",
                "",
                false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        verify(repository, never()).saveOrUpdate(any());
    }

    @Test
    void allowsWeakPasswordInLocalDevWhenInsecureExplicitlyAllowed() throws Exception {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("change-me")).thenReturn("weak-hash");

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "admin",
                "change-me",
                "shot-user",
                "",
                true);

        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        verify(repository).saveOrUpdate(new UserAccount("admin", "weak-hash", true, java.util.List.of("ROLE_USER")));
    }

    @Test
    void allowsStrongPasswordWithoutInsecureFlag() throws Exception {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("S3cure-Unique-Passphrase!")).thenReturn("strong-hash");

        DefaultUserBootstrap bootstrap = new DefaultUserBootstrap(
                repository,
                encoder,
                "operator",
                "S3cure-Unique-Passphrase!",
                "shot-user",
                "",
                false);

        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        verify(repository).saveOrUpdate(new UserAccount("operator", "strong-hash", true, java.util.List.of("ROLE_USER")));
    }
}
