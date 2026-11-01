package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.*;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.TwoStepSettingsService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.ratelimit.AccountEmailLimits;
import cc.hrva.urlshortener.util.EmailAddresses;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final TwoFactorService twoFactorService;
    private final TwoStepSettingsService twoStepSettingsService;
    private final HttpServletRequest request;
    private final ClientIpResolver clientIpResolver;
    private final AccountEmailLimits accountEmailLimits;

    @Operation(summary = "Register a new user", description = "Create a new user account with email and password.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "409", description = "Email already exists")
    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody final UserRegisterDto user) {
        log.info("Register controller invoked for user {}", EmailAddresses.masked(user.getEmail()));
        return ResponseEntity.ok(authService.register(user));
    }

    @Operation(summary = "Request password reset", description = "Send a password reset link to the given email address. The answer is the same whether or not an account uses it.")
    @ApiResponse(responseCode = "202", description = "Accepted; a link valid for 1 hour is emailed if an account uses the address and it got fewer than 3 account emails in the last hour")
    @ApiResponse(responseCode = "429", description = "Over 10 requests for account emails from this network in an hour, with Retry-After")
    @PostMapping("/password-reset")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody final RequestPasswordResetDto dto) {
        log.info("Forgot password controller invoked for {}", EmailAddresses.masked(dto.getEmail()));
        accountEmailLimits.checkNetwork(clientIpResolver.getClientIp(request));
        userService.sendPasswordResetLinkToUser(dto);

        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Confirm password reset", description = "Set a new password with the token from the reset email. A token works once, for 1 hour. Every session of the account ends.")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "410", description = "The link expired or was already used")
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody final PasswordResetDto passwordResetDto) {
        log.info("Password reset confirm controller invoked");
        userService.resetPassword(passwordResetDto);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Verify email", description = "Confirm a user's email address using the token sent at registration.")
    @ApiResponse(responseCode = "400", description = "Token invalid or expired")
    @PostMapping("/verify-email/confirm")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody final VerifyEmailDto dto) {
        log.info("Verify email controller invoked");
        authService.verifyEmail(dto.getToken());

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Resend verification email", description = "Resend the email verification link to the given address. The answer is the same whether or not an unverified account uses it.")
    @ApiResponse(responseCode = "202", description = "Accepted; a new link is emailed if an unverified account uses the address and it got fewer than 3 account emails in the last hour")
    @ApiResponse(responseCode = "429", description = "Over 10 requests for account emails from this network in an hour, with Retry-After")
    @PostMapping("/verify-email/resend")
    public ResponseEntity<Void> resendVerificationEmail(@Valid @RequestBody final ResendVerificationDto dto) {
        log.info("Resend verification email controller invoked for {}", EmailAddresses.masked(dto.getEmail()));
        accountEmailLimits.checkNetwork(clientIpResolver.getClientIp(request));
        authService.resendVerificationEmail(dto.getEmail());

        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Start 2FA setup", description = "Generate a TOTP secret and otpauth URI for the authenticated user to scan. Email and password, Google and GitHub accounts can all set up an authenticator app.")
    @ApiResponse(responseCode = "400", description = "An authenticator app is already set up")
    @PostMapping("/2fa/setup")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<TwoFactorSetupResponse> setupTwoFactor() {
        return ResponseEntity.ok(twoFactorService.setup());
    }

    @Operation(summary = "Enable 2FA", description = "Confirm the TOTP code to enable 2FA and receive one-time recovery codes.")
    @ApiResponse(responseCode = "400", description = "Invalid code or setup not started")
    @PostMapping("/2fa/enable")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<TwoFactorEnableResponse> enableTwoFactor(@Valid @RequestBody final TwoFactorCodeDto dto) {
        return ResponseEntity.ok(twoFactorService.enable(dto.getCode()));
    }

    @Operation(summary = "Disable 2FA", description = "Turn two-step verification off with a current TOTP or recovery code, the same as POST /api/v1/users/me/two-step/disable with a code. Passkeys and security keys stay.")
    @ApiResponse(responseCode = "204", description = "Two-step verification turned off")
    @ApiResponse(responseCode = "400", description = "Invalid code or two-step verification is off")
    @ApiResponse(responseCode = "403", description = "Account locked after too many invalid codes")
    @PostMapping("/2fa/disable")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> disableTwoFactor(@Valid @RequestBody final TwoFactorCodeDto dto) {
        twoStepSettingsService.disable(TwoStepDisableDto.builder().code(dto.getCode()).build());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Login", description = "Authenticate with email and password and receive a JWT token. When two-step verification is on, the response has no token but twoStepRequired, a pendingToken valid for 5 minutes and the methods the account can use; finish with POST /api/v1/auth/2fa/totp. Sending code with the password is deprecated.")
    @ApiResponse(responseCode = "401", description = "Bad credentials")
    @ApiResponse(responseCode = "429", description = "Too many sign-in attempts from this network or for this account, with Retry-After")
    @ApiResponse(responseCode = "503", description = "Password sign-in is paused while the sign-in limit can't count, with Retry-After")
    @PostMapping("/login")
    public ResponseEntity<JWTTokenDto> login(@Valid @RequestBody final LoginDto login) {
        log.info("Login controller invoked for user {}", EmailAddresses.masked(login.getEmail()));
        return authService.login(login, request);
    }
}
