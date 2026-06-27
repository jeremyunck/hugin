package com.example.integration.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AuthSettingRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthSettingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findValue(String key) {
        List<String> matches = jdbcTemplate.query(
                "select setting_value from app_settings where setting_key = ?",
                (rs, rowNum) -> rs.getString("setting_value"),
                key);
        return matches.stream().findFirst().filter(value -> value != null && !value.isBlank());
    }

    public void insert(String key, String value) {
        jdbcTemplate.update(
                "insert into app_settings (setting_key, setting_value) values (?, ?)",
                key, value);
    }

    public void insertIfAbsent(String key, String value) {
        try {
            insert(key, value);
        } catch (DuplicateKeyException ignored) {
            // Another startup won the race to persist the key; the caller will re-read the stored row.
        }
    }
}
