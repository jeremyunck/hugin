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
import java.util.regex.Pattern;

/**
 * Email + password authentication with a mandatory 6-digit email verification step.
 *
 * <p>Both registration and login are two-step:
 * <ol>
 *   <li>{@link #register} / {@link #login} validate the credentials and email a one-time code via
 *       {@link EmailVerificationService} (no session is issued yet).</li>
 *   <li>{@link #verify} confirms the code; for a pending registration it creates the {@code ROLE_USER}
 *       account, and in both cases it mints the JWT session.</li>
 * </ol>
 * The email address is the account's identity: it is stored as the unique username (the JWT subject
 * and the owner key used across the schema) as well as in the email column.
 */
@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountRepository userAccountRepository;
    private final EmailVerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuthJwtProperties jwtProperties;

    public AuthService(
            UserAccountRepository userAccountRepository,
            EmailVerificationService verificationService,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AuthJwtProperties jwtProperties) {
        this.userAccountRepository = userAccountRepository;
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    /** Step 1 of sign-up: validate the new credentials and email a verification code. */
    public AuthChallengeResponse register(AuthRegisterRequest request) {
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        String confirm = request.confirmPassword() == null ? "" : request.confirmPassword();

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (!password.equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match");
        }
        if (userAccountRepository.findByUsername(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        String passwordHash = passwordEncoder.encode(password);
        verificationService.startChallenge(email, EmailVerificationService.Purpose.REGISTER, passwordHash);
        return new AuthChallengeResponse(email, true, "We sent a 6-digit verification code to " + email);
    }

    /** Step 1 of login: validate the password and email a verification code. */
    public AuthChallengeResponse login(AuthLoginRequest request) {
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        if (email.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }

        UserAccount user = userAccountRepository.findByUsername(email)
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        verificationService.startChallenge(email, EmailVerificationService.Purpose.LOGIN, null);
        return new AuthChallengeResponse(email, true, "We sent a 6-digit verification code to " + email);
    }

    /** Step 2 (shared): confirm the code, create the account if registering, and issue a session. */
    public AuthLoginResponse verify(AuthVerifyRequest request) {
        String email = normalizeEmail(request.email());
        EmailVerificationService.Challenge challenge = verificationService.verify(email, request.code());

        UserAccount user;
        if (challenge.purpose() == EmailVerificationService.Purpose.REGISTER) {
            if (userAccountRepository.findByUsername(email).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
            }
            userAccountRepository.createUser(email, challenge.passwordHash());
            user = userAccountRepository.findByUsername(email)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Failed to create account"));
        } else {
            user = userAccountRepository.findByUsername(email)
                    .filter(UserAccount::enabled)
                    .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        }

        return issueToken(user);
    }

    public AuthMeResponse currentUser(org.springframework.security.oauth2.jwt.Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        UserAccount user = userAccountRepository.findByUsername(jwt.getSubject()).orElse(null);
        String displayName = user != null ? user.displayName() : null;
        String email = user != null ? user.email() : null;
        String customInstructions = user != null ? user.customInstructions() : null;
        return new AuthMeResponse(jwt.getSubject(), roles == null ? List.of() : roles,
                jwt.getIssuedAt(), jwt.getExpiresAt(), displayName, email, customInstructions);
    }

    private AuthLoginResponse issueToken(UserAccount user) {
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

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
