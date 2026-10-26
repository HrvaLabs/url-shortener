package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.TwoFactorCodeDto;
import cc.hrva.urlshortener.dto.TwoFactorEnableResponse;
import cc.hrva.urlshortener.dto.TwoStepDisableDto;
import cc.hrva.urlshortener.dto.TwoStepStatusResponse;
import cc.hrva.urlshortener.service.TwoStepSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/two-step")
public class TwoStepSettingsController {

    private final TwoStepSettingsService twoStepSettingsService;

    @Operation(summary = "Two-step verification status", description = "Whether two-step verification is on, whether an authenticator app is set up, how many passkeys and security keys the account has, and how many recovery codes are unused.")
    @ApiResponse(responseCode = "200", description = "Status")
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<TwoStepStatusResponse> status() {
        return ResponseEntity.ok(twoStepSettingsService.status());
    }

    @Operation(summary = "Turn on two-step verification with passkeys", description = "Turns two-step verification on without an authenticator app; the account needs at least one passkey or security key. Returns 10 new recovery codes, shown only once, or an empty list when unused recovery codes remain.")
    @ApiResponse(responseCode = "200", description = "Two-step verification turned on")
    @ApiResponse(responseCode = "400", description = "No passkey or security key yet, or two-step verification is already on")
    @PostMapping("/enable")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<TwoFactorEnableResponse> enableWithPasskeys() {
        return ResponseEntity.ok(twoStepSettingsService.enableWithPasskeys());
    }

    @Operation(summary = "Start turning off two-step verification with a passkey", description = "Returns the WebAuthn request options for the account's passkeys and security keys (allowCredentials lists them all, user verification preferred) and a single-use ceremonyId valid for 5 minutes.")
    @ApiResponse(responseCode = "200", description = "Request options")
    @ApiResponse(responseCode = "400", description = "Two-step verification is off, or the account has no passkeys or security keys")
    @ApiResponse(responseCode = "503", description = "Passkeys are unavailable")
    @PostMapping("/disable/options")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<PasskeyOptionsResponse> disableOptions() {
        return ResponseEntity.ok(twoStepSettingsService.disableOptions());
    }

    @Operation(summary = "Turn off two-step verification", description = "Confirm with exactly one second factor: a code (TOTP or recovery code), or the ceremonyId from the turn-off options with the browser's credential (PublicKeyCredential.toJSON()). Removes the authenticator app and the recovery codes; passkeys and security keys stay. Wrong codes and refused passkeys count toward the account's two-factor lock.")
    @ApiResponse(responseCode = "204", description = "Two-step verification turned off")
    @ApiResponse(responseCode = "400", description = "Two-step verification is off, not exactly one factor, an invalid code, or an unreadable credential")
    @ApiResponse(responseCode = "401", description = "The passkey isn't registered, isn't this account's, or couldn't be verified")
    @ApiResponse(responseCode = "403", description = "Account locked after too many invalid codes")
    @ApiResponse(responseCode = "410", description = "The ceremony expired or belongs to another request")
    @ApiResponse(responseCode = "503", description = "Passkeys are unavailable")
    @PostMapping("/disable")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> disable(@RequestBody(required = false) final TwoStepDisableDto dto) {
        twoStepSettingsService.disable(dto != null ? dto : new TwoStepDisableDto());

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove the authenticator app", description = "Confirm with a code (TOTP or recovery code). Two-step verification and the recovery codes stay; it needs a passkey or security key left as a two-step method. Wrong codes count toward the account's two-factor lock.")
    @ApiResponse(responseCode = "204", description = "Authenticator app removed")
    @ApiResponse(responseCode = "400", description = "No authenticator app, it is the only two-step method, or an invalid code")
    @ApiResponse(responseCode = "403", description = "Account locked after too many invalid codes")
    @DeleteMapping("/totp")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> removeTotp(@Valid @RequestBody final TwoFactorCodeDto dto) {
        twoStepSettingsService.removeTotp(dto);

        return ResponseEntity.noContent().build();
    }

}
