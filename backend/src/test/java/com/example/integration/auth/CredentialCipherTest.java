package com.example.integration.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {

    @Test
    void encryptThenDecryptRoundTrips() {
        CredentialCipher cipher = new CredentialCipher("test-secret");
        String plaintext = "sk-or-v1-abcdef0123456789";

        String token = cipher.encrypt(plaintext);

        assertThat(token).isNotNull().isNotEqualTo(plaintext).startsWith("v1:");
        assertThat(cipher.decrypt(token)).isEqualTo(plaintext);
    }

    @Test
    void encryptionIsNondeterministic() {
        CredentialCipher cipher = new CredentialCipher("test-secret");

        // A fresh random IV per call means identical plaintext yields different ciphertext.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void decryptWithDifferentSecretFails() {
        String token = new CredentialCipher("secret-a").encrypt("value");

        assertThatThrownBy(() -> new CredentialCipher("secret-b").decrypt(token))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        CredentialCipher cipher = new CredentialCipher("test-secret");
        String token = cipher.encrypt("value");

        // Flip a character in the base64 payload; GCM's auth tag must reject it.
        char[] chars = token.toCharArray();
        int idx = token.length() - 2;
        chars[idx] = chars[idx] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullValuesPassThrough() {
        CredentialCipher cipher = new CredentialCipher("test-secret");

        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}
