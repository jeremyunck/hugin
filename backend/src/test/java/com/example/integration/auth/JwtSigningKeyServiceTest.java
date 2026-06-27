package com.example.integration.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.crypto.SecretKey;
import java.sql.Connection;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSigningKeyServiceTest {

    private AuthSettingRepository repository;
    private JwtSigningKeyService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:jwt-signing-key-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        repository = new AuthSettingRepository(new JdbcTemplate(dataSource));
        service = new JwtSigningKeyService(repository);
    }

    @Test
    void generatesAndPersistsSecretWhenDatabaseIsEmpty() {
        SecretKey first = service.loadOrCreate(null);
        SecretKey second = service.loadOrCreate(null);
        String stored = repository.findValue(JwtSigningKeyService.SETTING_KEY).orElseThrow();

        assertThat(first.getEncoded()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(second.getEncoded()).isEqualTo(first.getEncoded());
        assertThat(Base64.getDecoder().decode(stored)).isEqualTo(first.getEncoded());
    }

    @Test
    void persistsConfiguredSecretWhenDatabaseIsEmpty() {
        byte[] configuredBytes = new byte[32];
        for (int i = 0; i < configuredBytes.length; i++) {
            configuredBytes[i] = (byte) (255 - i);
        }
        String configured = Base64.getEncoder().encodeToString(configuredBytes);

        SecretKey key = service.loadOrCreate(configured);

        assertThat(key.getEncoded()).isEqualTo(configuredBytes);
        assertThat(repository.findValue(JwtSigningKeyService.SETTING_KEY)).contains(configured);
    }

    @Test
    void prefersPersistedSecretOverDifferentConfiguredSecret() {
        String stored = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] configuredBytes = new byte[32];
        configuredBytes[0] = 7;
        String configured = Base64.getEncoder().encodeToString(configuredBytes);
        repository.insert(JwtSigningKeyService.SETTING_KEY, stored);

        SecretKey key = service.loadOrCreate(configured);

        assertThat(key.getEncoded()).isEqualTo(new byte[32]);
        assertThat(repository.findValue(JwtSigningKeyService.SETTING_KEY)).contains(stored);
    }
}
