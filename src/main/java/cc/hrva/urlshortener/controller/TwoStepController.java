package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySecondStepDto;
import cc.hrva.urlshortener.dto.PendingTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.service.SecondStepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/auth/2fa")
public class TwoStepController {

    private final SecondStepService secondStepService;
    private final HttpServletRequest request;

    @Operation(summary = "Finish a two-step sign-in with a code", description = "Send the pendingToken from the login response with a TOTP code from the authenticator app or a recovery code. Returns the login body and the Authorization header.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Invalid two-factor code")
    @ApiResponse(responseCode = "403", description = "Network or account blocked after too many attempts, account deactivated, or email not verified")
    @ApiResponse(responseCode = "410", description = "The sign-in expired, was used, or ran out of attempts")
    @ApiResponse(responseCode = "503", description = "Two-step sign-in is unavailable")
    @PostMapping("/totp")
    public ResponseEntity<JWTTokenDto> completeWithCode(@Valid @RequestBody final TotpSecondStepDto dto) {
        return secondStepService.completeWithCode(dto, request);
    }

    @Operation(summary = "Start the second step with a passkey or security key", description = "Send the pendingToken from the login response. Returns the WebAuthn request options for the account's passkeys and security keys (allowCredentials lists them all, user verification preferred) and a single-use ceremonyId valid for 5 minutes. Asking for options doesn't use one of the sign-in's 5 attempts.")
    @ApiResponse(responseCode = "200", description = "Request options")
    @ApiResponse(responseCode = "400", description = "The account has no passkeys or security keys")
    @ApiResponse(responseCode = "403", description = "Network blocked after too many attempts")
    @ApiResponse(responseCode = "410", description = "The sign-in expired, was used, or ran out of attempts")
    @ApiResponse(responseCode = "503", description = "Two-step sign-in or passkeys are unavailable")
    @PostMapping("/webauthn/options")
    public ResponseEntity<PasskeyOptionsResponse> passkeyOptions(@Valid @RequestBody final PendingTokenDto dto) {
        return ResponseEntity.ok(secondStepService.passkeyOptions(dto, request));
    }

    @Operation(summary = "Finish a two-step sign-in with a passkey or security key", description = "Send the pendingToken, the ceremonyId from the second-step options and the browser's credential (PublicKeyCredential.toJSON()). Returns the login body and the Authorization header. A failed attempt uses one of the sign-in's 5 attempts, shared with codes.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "The credential is missing or couldn't be read")
    @ApiResponse(responseCode = "401", description = "The passkey isn't registered, isn't this account's, or couldn't be verified")
    @ApiResponse(responseCode = "403", description = "Network or account blocked after too many attempts, account deactivated, or email not verified")
    @ApiResponse(responseCode = "410", description = "The sign-in expired, was used, or ran out of attempts, or the ceremony expired or belongs to another request")
    @ApiResponse(responseCode = "503", description = "Two-step sign-in or passkeys are unavailable")
    @PostMapping("/webauthn/verify")
    public ResponseEntity<JWTTokenDto> completeWithPasskey(@Valid @RequestBody final PasskeySecondStepDto dto) {
        return secondStepService.completeWithPasskey(dto, request);
    }

}
