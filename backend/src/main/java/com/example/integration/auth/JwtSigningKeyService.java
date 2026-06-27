package com.example.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Service
public class JwtSigningKeyService {

    static final String SETTING_KEY = "auth.jwt.secret-base64";

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKeyService.class);

    private final AuthSettingRepository authSettingRepository;

    public JwtSigningKeyService(AuthSettingRepository authSettingRepository) {
        this.authSettingRepository = authSettingRepository;
    }

    public SecretKey loadOrCreate(String configuredSecretBase64) {
        String stored = authSettingRepository.findValue(SETTING_KEY).orElse(null);
        if (stored != null) {
            if (configuredSecretBase64 != null
                    && !configuredSecretBase64.isBlank()
                    && !configuredSecretBase64.equals(stored)) {
                log.info("Ignoring auth.jwt.secret-base64 because a persisted JWT signing key already exists");
            }
            return decodeSecret(stored);
        }

        String initialSecret = configuredSecretBase64 == null || configuredSecretBase64.isBlank()
                ? generateSecretBase64()
                : configuredSecretBase64;
        if (configuredSecretBase64 == null || configuredSecretBase64.isBlank()) {
            log.info("Generated and persisted a JWT signing key in app_settings");
        } else {
            log.info("Persisted auth.jwt.secret-base64 into app_settings for future restarts");
        }

        // Validate before inserting so a bad configured secret fails fast rather than being persisted.
        decodeSecret(initialSecret);
        authSettingRepository.insertIfAbsent(SETTING_KEY, initialSecret);
        return decodeSecret(authSettingRepository.findValue(SETTING_KEY).orElse(initialSecret));
    }

    private static SecretKey decodeSecret(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("auth.jwt.secret-base64 must be valid base64", ex);
        }

        if (decoded.length < 32) {
            throw new IllegalStateException("auth.jwt.secret-base64 must decode to at least 32 bytes");
        }

        return new SecretKeySpec(decoded, "HmacSHA256");
    }

    private static String generateSecretBase64() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("HmacSHA256");
            keyGenerator.init(256);
            return Base64.getEncoder().encodeToString(keyGenerator.generateKey().getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate a JWT signing key", ex);
        }
    }
}
