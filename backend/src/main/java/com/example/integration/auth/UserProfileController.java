package com.example.integration.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/user")
public class UserProfileController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService verificationService;

    public UserProfileController(UserAccountRepository userAccountRepository,
                                  PasswordEncoder passwordEncoder,
                                  EmailVerificationService verificationService) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationService = verificationService;
    }

    @GetMapping("/profile")
    public UserProfileResponse getProfile(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userAccountRepository.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return new UserProfileResponse(user.username(), user.displayName(), user.email(), user.customInstructions());
    }

    @PutMapping("/profile")
    public UserProfileResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                              @RequestBody UpdateProfileRequest request) {
        String username = jwt.getSubject();
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        String email = request.email() == null ? null : request.email().trim();
        String customInstructions = request.customInstructions() == null ? null : request.customInstructions().trim();

        if (displayName != null && displayName.isBlank()) displayName = null;
        if (email != null && email.isBlank()) email = null;
        if (customInstructions != null && customInstructions.isBlank()) customInstructions = null;

        userAccountRepository.updateProfile(username, displayName, email, customInstructions);

        UserAccount updated = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return new UserProfileResponse(updated.username(), updated.displayName(), updated.email(), updated.customInstructions());
    }

    @PutMapping("/password")
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                                @RequestBody ChangePasswordRequest request) {
        String username = jwt.getSubject();
        if (request.currentPassword() == null || request.currentPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is required");
        }
        if (request.newPassword() == null || request.newPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password is required");
        }
        if (request.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters");
        }

        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        userAccountRepository.updatePassword(username, passwordEncoder.encode(request.newPassword()));
    }

    /**
     * Step 1 of a verified password reset: validate the new password and email a verification code to
     * the account holder. The hashed new password is held with the challenge and only applied once the
     * code is confirmed via {@link #confirmPasswordReset}.
     */
    @PostMapping("/password/reset/request")
    public AuthChallengeResponse requestPasswordReset(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody ResetPasswordRequest request) {
        String username = jwt.getSubject();
        if (request.newPassword() == null || request.newPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password is required");
        }
        if (request.newPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String passwordHash = passwordEncoder.encode(request.newPassword());
        verificationService.startChallenge(user.username(),
                EmailVerificationService.Purpose.PASSWORD_RESET, passwordHash);
        return new AuthChallengeResponse(user.username(), true,
                "We sent a 6-digit verification code to " + user.username());
    }

    /** Step 2 of a verified password reset: confirm the code and persist the new password. */
    @PostMapping("/password/reset/confirm")
    public void confirmPasswordReset(@AuthenticationPrincipal Jwt jwt,
                                     @RequestBody ConfirmResetRequest request) {
        String username = jwt.getSubject();
        EmailVerificationService.Challenge challenge = verificationService.verify(username, request.code());
        if (challenge.purpose() != EmailVerificationService.Purpose.PASSWORD_RESET
                || challenge.passwordHash() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No password reset in progress. Please start over.");
        }
        userAccountRepository.updatePassword(username, challenge.passwordHash());
    }

    public record UserProfileResponse(
            String username,
            String displayName,
            String email,
            String customInstructions) {}

    public record UpdateProfileRequest(
            String displayName,
            String email,
            String customInstructions) {}

    public record ChangePasswordRequest(
            String currentPassword,
            String newPassword) {}

    public record ResetPasswordRequest(String newPassword) {}

    public record ConfirmResetRequest(String code) {}
}
