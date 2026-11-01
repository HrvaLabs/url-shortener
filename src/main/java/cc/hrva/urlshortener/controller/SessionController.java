package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.SessionsEndedResponse;
import cc.hrva.urlshortener.security.CurrentSession;
import cc.hrva.urlshortener.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final CurrentSession currentSession;

    @Operation(summary = "Sign out everywhere",
            description = "Ends every sign-in of the account and answers a new token for this device, so only the others are signed out. "
                    + "A token issued before this answers a 401 problem with detail \"Your session ended. Sign in again.\". API keys and connected MCP apps keep working.")
    @ApiResponse(responseCode = "200", description = "The other devices are signed out; use the new token from now on")
    @ApiResponse(responseCode = "401", description = "Not signed in, or this session already ended")
    @PostMapping("/end")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<SessionsEndedResponse> endEverywhere() {
        sessionService.endEverywhere();
        final var headers = currentSession.keepThisDeviceSignedIn();

        return ResponseEntity.ok().headers(headers).body(new SessionsEndedResponse(headers.getFirst(CurrentSession.TOKEN_HEADER)));
    }

}
