package com.example.integration.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultUserBootstrap implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public DefaultUserBootstrap(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            @Value("${auth.bootstrap.username:test}") String username,
            @Value("${auth.bootstrap.password:}") String password) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ((password == null || password.isBlank()) && userAccountRepository.findByUsername(username).isPresent()) {
            return;
        }
        userAccountRepository.saveOrUpdate(
                new UserAccount(username, passwordEncoder.encode(resolvePassword()), true, List.of("ROLE_USER")));
    }

    private String resolvePassword() {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "auth.bootstrap.password must be set; refusing to start with a default password");
        }
        return password;
    }
}
