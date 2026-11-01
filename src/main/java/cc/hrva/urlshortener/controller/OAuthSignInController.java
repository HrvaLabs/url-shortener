package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.OAuthCodeDto;
import cc.hrva.urlshortener.service.OAuthSignInService;
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
@RequestMapping("api/v1/auth/oauth")
public class OAuthSignInController {

    private final OAuthSignInService oAuthSignInService;
    private final HttpServletRequest request;

    @Operation(summary = "Finish a Google or GitHub sign-in",
            description = "Exchanges the one-time code from the sign-in callback (/auth/callback?code=…) for the same answer as POST /api/v1/auth/login. "
                    + "A code lives 60 seconds and works once.")
    @ApiResponse(responseCode = "200", description = "Signed in: token and user")
    @ApiResponse(responseCode = "400", description = "No code")
    @ApiResponse(responseCode = "403", description = "The account is deactivated")
    @ApiResponse(responseCode = "410", description = "The code is unknown, used or expired; sign in again")
    @ApiResponse(responseCode = "429", description = "Too many sign-in attempts from this network")
    @ApiResponse(responseCode = "503", description = "Signing in isn't available right now")
    @PostMapping("/exchange")
    public ResponseEntity<JWTTokenDto> exchange(@Valid @RequestBody final OAuthCodeDto dto) {
        return oAuthSignInService.exchange(dto.getCode(), request);
    }

}
