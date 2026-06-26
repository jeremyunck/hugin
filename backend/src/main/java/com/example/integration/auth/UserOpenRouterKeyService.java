package com.example.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Manages each user's personal OpenRouter API key. Keys are encrypted at rest by
 * {@link CredentialCipher} and only ever resolved for the user they belong to, so one user's key can
 * never be read or used by another. The raw key is never returned to clients — callers can learn that
 * a key exists and see a masked suffix, nothing more.
 */
@Service
public class UserOpenRouterKeyService {

    private static final Logger log = LoggerFactory.getLogger(UserOpenRouterKeyService.class);

    private final UserAccountRepository repository;
    private final CredentialCipher cipher;

    public UserOpenRouterKeyService(UserAccountRepository repository, CredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /** The user's decrypted OpenRouter key, or empty when none is stored or it can't be decrypted. */
    public Optional<String> resolveApiKey(String username) {
        return repository.findOpenRouterApiKeyEncrypted(username).flatMap(encrypted -> {
            try {
                String decrypted = cipher.decrypt(encrypted);
                return Optional.ofNullable(decrypted).filter(value -> !value.isBlank());
            } catch (RuntimeException e) {
                // Most likely the encryption secret changed since the key was written. Treat it as
                // "no key" rather than failing the user's request.
                log.warn("Could not decrypt stored OpenRouter key for user {}: {}", username, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Encrypts and stores a user's OpenRouter key (trimmed). */
    public void saveApiKey(String username, String rawKey) {
        String trimmed = rawKey == null ? "" : rawKey.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("API key must not be blank");
        }
        repository.updateOpenRouterApiKey(username, cipher.encrypt(trimmed));
    }

    /** Removes any stored OpenRouter key for the user. */
    public void clearApiKey(String username) {
        repository.updateOpenRouterApiKey(username, null);
    }

    /** Whether the user has an OpenRouter key on file (without decrypting it). */
    public boolean hasApiKey(String username) {
        return repository.findOpenRouterApiKeyEncrypted(username).isPresent();
    }

    /** Last four characters of the stored key for display (e.g. {@code ••••abcd}), if resolvable. */
    public Optional<String> maskedSuffix(String username) {
        return resolveApiKey(username)
                .filter(key -> key.length() >= 4)
                .map(key -> key.substring(key.length() - 4));
    }
}
