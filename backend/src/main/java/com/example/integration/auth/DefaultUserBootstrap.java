package com.example.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Seeds local login accounts on startup.
 *
 * <p>Security posture around the bootstrap credential:
 * <ul>
 *   <li>The password has no default, so the app never silently boots with a generic credential.</li>
 *   <li>A blank password (with no pre-existing account) refuses startup.</li>
 *   <li>A well-known weak/default password (e.g. {@code change-me}, {@code admin}, {@code password})
 *       refuses startup unless {@code auth.bootstrap.allow-insecure-password=true} is set, which is
 *       intended only for local development. In that case it is allowed but a loud warning is logged.</li>
 * </ul>
 *
 * <p>Set {@code AUTH_BOOTSTRAP_USERNAME} / {@code AUTH_BOOTSTRAP_PASSWORD} to provision the primary
 * account. For production, choose a strong, unique password and leave
 * {@code auth.bootstrap.allow-insecure-password} unset (defaults to {@code false}).
 */
@Component
public class DefaultUserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserBootstrap.class);

    /**
     * Well-known bootstrap/default credentials that must never be used unattended. Compared
     * case-insensitively. This is deliberately small and focused on the values that have shipped as
     * defaults or appear in docs/examples; it is not a general password-strength check.
     */
    static final Set<String> WEAK_PASSWORDS = Set.of(
            "change-me",
            "changeme",
            "change-me-please",
            "admin",
            "password",
            "passw0rd",
            "default",
            "secret",
            "bouw",
            "test");

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String screenshotUsername;
    private final String screenshotPassword;
    private final boolean allowInsecurePassword;

    public DefaultUserBootstrap(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            @Value("${auth.bootstrap.username:test}") String username,
            @Value("${auth.bootstrap.password:}") String password,
            @Value("${auth.test-user.username:screenshot-test}") String screenshotUsername,
            @Value("${auth.test-user.password:}") String screenshotPassword,
            @Value("${auth.bootstrap.allow-insecure-password:false}") boolean allowInsecurePassword) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.screenshotUsername = screenshotUsername;
        this.screenshotPassword = screenshotPassword;
        this.allowInsecurePassword = allowInsecurePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ((password == null || password.isBlank()) && userAccountRepository.findByUsername(username).isPresent()) {
            maybeSeedScreenshotUser();
            return;
        }
        ensureUser(username, resolvePassword());
        maybeSeedScreenshotUser();
    }

    /**
     * Seeds the screenshot/UI-verification account only when a password is explicitly configured
     * (via auth.test-user.password / AUTH_TEST_USER_PASSWORD). There is intentionally no default,
     * so the public build never ships with a known credential.
     */
    private void maybeSeedScreenshotUser() {
        if (screenshotPassword == null || screenshotPassword.isBlank()) {
            return;
        }
        ensureUser(screenshotUsername, screenshotPassword);
    }

    private String resolvePassword() {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "auth.bootstrap.password must be set; refusing to start with a default password");
        }
        if (isWeak(password)) {
            if (!allowInsecurePassword) {
                throw new IllegalStateException(
                        "auth.bootstrap.password is set to a well-known default/weak value; refusing to start. "
                                + "Set AUTH_BOOTSTRAP_PASSWORD to a strong, unique password, or set "
                                + "auth.bootstrap.allow-insecure-password=true for local development only.");
            }
            log.warn("\n*** SECURITY WARNING ***\n"
                    + "Bootstrap user '{}' is configured with a well-known/weak password. This is permitted only "
                    + "because auth.bootstrap.allow-insecure-password=true (local development). Do NOT expose this "
                    + "instance beyond localhost. Set AUTH_BOOTSTRAP_PASSWORD to a strong, unique value for any "
                    + "shared or production deployment.", username);
        }
        return password;
    }

    private static boolean isWeak(String candidate) {
        return WEAK_PASSWORDS.contains(candidate.trim().toLowerCase());
    }

    private void ensureUser(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Bootstrap usernames and passwords must not be blank");
        }
        userAccountRepository.saveOrUpdate(
                new UserAccount(username, passwordEncoder.encode(password), true, List.of("ROLE_USER")));
    }
}
