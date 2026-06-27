package com.example.integration.mcp;

import com.example.integration.auth.CredentialCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpSecretEncryptionServiceTest {

    private final McpSecretEncryptionService service =
            new McpSecretEncryptionService(new CredentialCipher("unit-test-secret"));

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        String encrypted = service.encrypt("my-bearer-token");
        assertThat(encrypted).isNotNull().doesNotContain("my-bearer-token");
        assertThat(service.decrypt(encrypted)).isEqualTo("my-bearer-token");
    }

    @Test
    void blankInputsReturnNull() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.encrypt("  ")).isNull();
        assertThat(service.decrypt(null)).isNull();
        assertThat(service.decrypt("")).isNull();
    }
}
