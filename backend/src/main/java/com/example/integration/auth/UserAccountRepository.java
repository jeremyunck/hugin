package com.example.integration.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class UserAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> matches = jdbcTemplate.query(
                """
                        select username, password_hash, enabled, roles, display_name, email, custom_instructions
                        from app_users
                        where username = ?
                        """,
                (rs, rowNum) -> new UserAccount(
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getBoolean("enabled"),
                        parseRoles(rs.getString("roles")),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("custom_instructions")),
                username);
        return matches.stream().findFirst();
    }

    /**
     * Creates a new email-based account. The email doubles as the unique username (the JWT subject
     * and the owner key used across the schema) and is stored in the dedicated email column too.
     * All accounts created through the public sign-up flow are {@code ROLE_USER}.
     */
    public void createUser(String email, String passwordHash) {
        jdbcTemplate.update(
                """
                        insert into app_users (username, password_hash, enabled, roles, email)
                        values (?, ?, true, 'ROLE_USER', ?)
                        """,
                email, passwordHash, email);
    }

    public void saveOrUpdate(UserAccount user) {
        int updated = jdbcTemplate.update(
                """
                        update app_users
                        set password_hash = ?, enabled = ?, roles = ?
                        where username = ?
                        """,
                user.passwordHash(), user.enabled(), joinRoles(user.roles()), user.username());
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            insert into app_users (username, password_hash, enabled, roles)
                            values (?, ?, ?, ?)
                            """,
                    user.username(), user.passwordHash(), user.enabled(), joinRoles(user.roles()));
        }
    }

    public void updateProfile(String username, String displayName, String email, String customInstructions) {
        jdbcTemplate.update(
                """
                        update app_users
                        set display_name = ?, email = ?, custom_instructions = ?
                        where username = ?
                        """,
                displayName, email, customInstructions, username);
    }

    public void updatePassword(String username, String newPasswordHash) {
        jdbcTemplate.update(
                """
                        update app_users
                        set password_hash = ?
                        where username = ?
                        """,
                newPasswordHash, username);
    }

    private static List<String> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of("ROLE_USER");
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .toList();
    }

    private static String joinRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "ROLE_USER";
        }
        return String.join(",", roles);
    }
}
