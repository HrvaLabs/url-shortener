package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.PasskeyDeleteDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyRenameDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.service.PasskeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/passkeys")
public class PasskeyController {

    private final PasskeyService passkeyService;

    @Operation(summary = "Start adding a passkey or security key", description = "Confirms it is the account owner (the current password for email and password accounts, a sign-in within the last 15 minutes for Google and GitHub accounts) and returns the WebAuthn creation options with a single-use ceremonyId, valid for 5 minutes.")
    @ApiResponse(responseCode = "200", description = "Creation options")
    @ApiResponse(responseCode = "400", description = "The current password is missing")
    @ApiResponse(responseCode = "401", description = "A Google or GitHub account must sign in again first")
    @ApiResponse(responseCode = "403", description = "The current password is incorrect")
    @ApiResponse(responseCode = "503", description = "Passkeys are unavailable")
    @PostMapping("/registration/options")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<PasskeyOptionsResponse> registrationOptions(@RequestBody(required = false) final PasskeyRegistrationOptionsDto dto) {
        return ResponseEntity.ok(passkeyService.registrationOptions(dto != null ? dto : new PasskeyRegistrationOptionsDto()));
    }

    @Operation(summary = "Add a passkey or security key", description = "Send the ceremonyId and the browser's credential (PublicKeyCredential.toJSON()). The optional label is trimmed, at most 64 characters, and blank becomes Passkey.")
    @ApiResponse(responseCode = "201", description = "Passkey added")
    @ApiResponse(responseCode = "400", description = "Unreadable credential, already added, or label too long")
    @ApiResponse(responseCode = "401", description = "The passkey or security key couldn't be verified")
    @ApiResponse(responseCode = "410", description = "The ceremony expired, was used, or belongs to another request")
    @ApiResponse(responseCode = "503", description = "Passkeys are unavailable")
    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<PasskeyResponse> register(@Valid @RequestBody final PasskeyRegistrationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(passkeyService.register(dto));
    }

    @Operation(summary = "List passkeys and security keys", description = "The authenticated user's passkeys and security keys, newest first.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<List<PasskeyResponse>> list() {
        return ResponseEntity.ok(passkeyService.list());
    }

    @Operation(summary = "Rename a passkey or security key", description = "The label is trimmed and at most 64 characters.")
    @ApiResponse(responseCode = "200", description = "Passkey renamed")
    @ApiResponse(responseCode = "404", description = "Not one of the user's passkeys")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<PasskeyResponse> rename(@PathVariable("id") final Long id, @Valid @RequestBody final PasskeyRenameDto dto) {
        return ResponseEntity.ok(passkeyService.rename(id, dto));
    }

    @Operation(summary = "Remove a passkey or security key", description = "Email and password accounts confirm with their current password; Google and GitHub accounts must have signed in within the last 15 minutes.")
    @ApiResponse(responseCode = "204", description = "Passkey removed")
    @ApiResponse(responseCode = "400", description = "The current password is missing")
    @ApiResponse(responseCode = "401", description = "A Google or GitHub account must sign in again first")
    @ApiResponse(responseCode = "403", description = "The current password is incorrect")
    @ApiResponse(responseCode = "404", description = "Not one of the user's passkeys")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> delete(@PathVariable("id") final Long id, @RequestBody(required = false) final PasskeyDeleteDto dto) {
        passkeyService.delete(id, dto != null ? dto : new PasskeyDeleteDto());

        return ResponseEntity.noContent().build();
    }

}
