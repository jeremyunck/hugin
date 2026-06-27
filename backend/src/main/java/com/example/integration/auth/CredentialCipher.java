package com.example.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Symmetric encryption for user-supplied secrets stored at rest (currently per-user OpenRouter API
 * keys). Uses AES-256-GCM, which provides both confidentiality and integrity, with a fresh random
 * 96-bit IV per value. The encryption key is derived by SHA-256 over the configured
 * {@code app.encryption.secret}; when that is unset a process-local fallback is used so development
 * works out of the box, with a warning that stored values will not survive a key change.
 *
 * <p>Ciphertext is encoded as {@code v1:base64(iv || ciphertext+tag)} so the scheme can evolve later.
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Built-in key material used only when no secret is configured (development convenience). */
    private static final String DEVELOPMENT_FALLBACK_SECRET = "bouw-insecure-development-key";

    public CredentialCipher(@Value("${app.encryption.secret:}") String secret) {
        String effectiveSecret;
        if (secret == null || secret.isBlank()) {
            log.warn("app.encryption.secret is not set; using a built-in development key that offers "
                    + "no real protection. Set APP_ENCRYPTION_SECRET to a strong, stable secret for "
                    + "production — changing it later makes previously stored credentials undecryptable.");
            effectiveSecret = DEVELOPMENT_FALLBACK_SECRET;
        } else {
            effectiveSecret = secret;
        }
        this.key = new SecretKeySpec(sha256(effectiveSecret.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    /** Encrypts {@code plaintext}, returning a self-describing, URL-safe-free token. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    /**
     * Decrypts a token produced by {@link #encrypt}. Returns {@code null} for {@code null} input and
     * throws {@link IllegalStateException} when the value is malformed or fails integrity verification
     * (e.g. the encryption secret changed since it was written).
     */
    public String decrypt(String token) {
        if (token == null) {
            return null;
        }
        if (!token.startsWith(PREFIX)) {
            throw new IllegalStateException("Unrecognized credential token format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(token.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
