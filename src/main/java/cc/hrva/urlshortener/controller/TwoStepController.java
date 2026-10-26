package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.JWTTokenDto;
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

}
