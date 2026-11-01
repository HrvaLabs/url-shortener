package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.RemovePasswordDto;
import cc.hrva.urlshortener.dto.SignInMethodTurnOnDto;
import cc.hrva.urlshortener.dto.SignInMethodsResponse;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.security.CurrentSession;
import cc.hrva.urlshortener.service.SignInMethodChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me")
public class SignInMethodController {

    private final SignInMethodChangeService signInMethodChangeService;
    private final CurrentSession currentSession;

    @Operation(summary = "Turn off Google or GitHub sign-in",
            description = "Keeps the method's row and refuses later sign-ins with it, and signs out every other device: the answer's "
                    + "Authorization header carries a new token for this one. The last way to sign in can't be turned off.")
    @ApiResponse(responseCode = "200", description = "The account's sign-in methods after the change")
    @ApiResponse(responseCode = "400", description = "PASSWORD or PASSKEY, or an unknown method")
    @ApiResponse(responseCode = "404", description = "The account has never signed in with this method")
    @ApiResponse(responseCode = "409", description = "It would leave no way to sign in")
    @PostMapping("/sign-in-methods/{method}/turn-off")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<SignInMethodsResponse> turnOff(@PathVariable("method") final SignInMethod method) {
        final var methods = signInMethodChangeService.turnOff(method);

        return ResponseEntity.ok().headers(currentSession.keepThisDeviceSignedIn()).body(methods);
    }

    @Operation(summary = "Turn Google or GitHub sign-in back on",
            description = "Needs the current password while the account has one, otherwise a sign-in within the last 15 minutes.")
    @ApiResponse(responseCode = "200", description = "The account's sign-in methods after the change")
    @ApiResponse(responseCode = "400", description = "PASSWORD or PASSKEY, an unknown method, or the current password is missing")
    @ApiResponse(responseCode = "401", description = "An account without a password must sign in again first")
    @ApiResponse(responseCode = "403", description = "The current password doesn't match, or too many wrong passwords")
    @ApiResponse(responseCode = "404", description = "The account has never signed in with this method")
    @PostMapping("/sign-in-methods/{method}/turn-on")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<SignInMethodsResponse> turnOn(@PathVariable("method") final SignInMethod method,
                                                        @RequestBody(required = false) final SignInMethodTurnOnDto dto) {
        return ResponseEntity.ok(signInMethodChangeService.turnOn(method, dto != null ? dto : new SignInMethodTurnOnDto()));
    }

    @Operation(summary = "Remove the account's password",
            description = "Needs the current password. Forgot password sets a new one. Signs out every other device: the answer's "
                    + "Authorization header carries a new token for this one. The last way to sign in can't be removed.")
    @ApiResponse(responseCode = "200", description = "The account's sign-in methods after the change")
    @ApiResponse(responseCode = "400", description = "The current password is missing or wrong")
    @ApiResponse(responseCode = "403", description = "Too many wrong passwords")
    @ApiResponse(responseCode = "404", description = "The account has no password")
    @ApiResponse(responseCode = "409", description = "It would leave no way to sign in")
    @PostMapping("/password/remove")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<SignInMethodsResponse> removePassword(@Valid @RequestBody final RemovePasswordDto dto) {
        final var methods = signInMethodChangeService.removePassword(dto);

        return ResponseEntity.ok().headers(currentSession.keepThisDeviceSignedIn()).body(methods);
    }

}
