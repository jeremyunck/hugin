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
                        select username, password_hash, enabled, roles
                        from app_users
                        where username = ?
                        """,
                (rs, rowNum) -> new UserAccount(
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getBoolean("enabled"),
                        parseRoles(rs.getString("roles"))),
                username);
        return matches.stream().findFirst();
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
