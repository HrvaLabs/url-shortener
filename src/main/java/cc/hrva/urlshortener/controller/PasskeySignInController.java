package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySignInDto;
import cc.hrva.urlshortener.service.PasskeySignInService;
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
@RequestMapping("api/v1/auth/passkeys")
public class PasskeySignInController {

    private final PasskeySignInService passkeySignInService;
    private final HttpServletRequest request;

    @Operation(summary = "Start a passkey sign-in", description = "Returns the WebAuthn request options for signing in with a passkey, with no email or password, and a single-use ceremonyId valid for 5 minutes. allowCredentials is empty, so the browser offers the passkeys saved for this site. 30 requests per 10 minutes per network.")
    @ApiResponse(responseCode = "200", description = "Request options")
    @ApiResponse(responseCode = "429", description = "Too many passkey sign-in attempts from this network")
    @ApiResponse(responseCode = "503", description = "Passkeys are unavailable")
    @PostMapping("/options")
    public ResponseEntity<PasskeyOptionsResponse> options() {
        return ResponseEntity.ok(passkeySignInService.options(request));
    }

    @Operation(summary = "Sign in with a passkey", description = "Send the ceremonyId and the browser's credential (PublicKeyCredential.toJSON()). Returns the login body and the Authorization header. No second step is asked for, even with two-step verification on: a passkey with user verification is already two factors.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "The credential is missing or couldn't be read")
    @ApiResponse(responseCode = "401", description = "The passkey isn't registered, or couldn't be verified")
    @ApiResponse(responseCode = "403", description = "Network blocked after too many attempts, account deactivated, or email not verified")
    @ApiResponse(responseCode = "410", description = "The ceremony expired, was used, or belongs to another request")
    @ApiResponse(responseCode = "503", description = "Passkeys are unavailable")
    @PostMapping("/verify")
    public ResponseEntity<JWTTokenDto> verify(@Valid @RequestBody final PasskeySignInDto dto) {
        return passkeySignInService.verify(dto, request);
    }

}
