package com.example.integration.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuthJwtProperties jwtProperties;

    public AuthService(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AuthJwtProperties jwtProperties) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    public AuthLoginResponse login(AuthLoginRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (username.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        UserAccount user = userAccountRepository.findByUsername(username)
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtProperties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.username())
                .claim("roles", user.roles())
                .claim("enabled", true)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims)).getTokenValue();

        return new AuthLoginResponse(token, "Bearer", expiresAt, user.username(), user.roles());
    }

    public AuthMeResponse currentUser(org.springframework.security.oauth2.jwt.Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthMeResponse(jwt.getSubject(), roles == null ? List.of() : roles, jwt.getIssuedAt(), jwt.getExpiresAt());
    }
}
