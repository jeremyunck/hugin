package com.example.integration.mcp;

import com.example.integration.auth.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Encrypts and decrypts MCP bearer tokens at rest.
 *
 * <p>This reuses the application's existing AES-256-GCM {@link CredentialCipher} rather than
 * introducing a second key — the encryption secret is configured via {@code app.encryption.secret}
 * (which falls back to {@code HUGIN_SECRET_KEY}; see {@code application.yml}). A round-trip self-test
 * runs at startup so a broken cipher configuration fails fast with a clear message instead of
 * silently corrupting stored tokens.
 *
 * <p>Bearer tokens are NEVER stored in plaintext and NEVER logged. Only the MCP package calls
 * {@link #decrypt} — and only to attach the token to an outbound request — so the raw value never
 * leaves the server.
 */
@Service
public class McpSecretEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(McpSecretEncryptionService.class);

    private final CredentialCipher cipher;

    public McpSecretEncryptionService(CredentialCipher cipher) {
        this.cipher = cipher;
        verifyCipher();
    }

    /** Encrypts a bearer token for storage. Returns {@code null} for a blank/absent token. */
    public String encrypt(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            return null;
        }
        return cipher.encrypt(plaintextToken.trim());
    }

    /**
     * Decrypts a stored token. Returns {@code null} for a blank input. Throws when the value is
     * malformed or fails integrity verification (e.g. the encryption secret changed).
     */
    public String decrypt(String encryptedToken) {
        if (encryptedToken == null || encryptedToken.isBlank()) {
            return null;
        }
        return cipher.decrypt(encryptedToken);
    }

    /**
     * Fails startup if encryption cannot perform a basic round-trip, surfacing a configuration error
     * up front rather than when the first token is saved.
     */
    private void verifyCipher() {
        try {
            String probe = "mcp-encryption-self-test";
            String roundTripped = cipher.decrypt(cipher.encrypt(probe));
            if (!probe.equals(roundTripped)) {
                throw new IllegalStateException("encrypt/decrypt round-trip did not match");
            }
            log.info("MCP secret encryption initialized.");
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "MCP secret encryption could not initialize. Set a stable app.encryption.secret "
                            + "(HUGIN_SECRET_KEY / APP_ENCRYPTION_SECRET) so MCP bearer tokens can be "
                            + "encrypted at rest. Cause: " + e.getMessage(), e);
        }
    }
}
