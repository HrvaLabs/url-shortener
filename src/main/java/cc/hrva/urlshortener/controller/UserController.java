package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.DataExportDto;
import cc.hrva.urlshortener.dto.DeleteAccountDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.UserService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Get current user", description = "Retrieve details of the currently authenticated user.")
    @GetMapping("/me")
    public ResponseEntity<UserDto> currentUser() {
        return ResponseEntity.ok(userService.fetchCurrentUser());
    }

    @Hidden
    @Operation(summary = "Get all users (admin)", description = "Retrieve paginated list of all users. Supports filtering by search and active. Requires ROLE_ADMIN.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<User>> fetchAllUsers(
            @PageableDefault(size = 20) final Pageable pageable,
            @ModelAttribute final UserSearchDto search) {
        return ResponseEntity.ok(userService.fetchAllUsers(pageable, search));
    }

    @Hidden
    @Operation(summary = "Delete a user (admin)", description = "Permanently delete a user by ID. Requires ROLE_ADMIN.")
    @ApiResponse(responseCode = "404", description = "User not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.DELETE_USER)
    public ResponseEntity<Void> deleteUser(@PathVariable("id") final Long id) {
        userService.deleteUserById(id);

        return ResponseEntity.noContent().build();
    }

    @Hidden
    @Operation(summary = "Update a user (admin)", description = "Update user details (email, slots, active status). Requires ROLE_ADMIN.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "404", description = "User not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.UPDATE_USER)
    public ResponseEntity<User> updateUser(
            @PathVariable final Long id,
            @Valid @RequestBody final UserUpdateDto userUpdateDto) {
        userUpdateDto.setId(id);
        return ResponseEntity.ok(userService.updateUser(userUpdateDto));
    }

    @Operation(summary = "Delete own account", description = "Permanently delete the authenticated user's account along with all their URLs and API keys. Local accounts must confirm with their current password; Google and GitHub accounts need a sign-in within the last 15 minutes.")
    @ApiResponse(responseCode = "204", description = "Account deleted")
    @ApiResponse(responseCode = "400", description = "The current password is missing")
    @ApiResponse(responseCode = "401", description = "A Google or GitHub account must sign in again first")
    @ApiResponse(responseCode = "403", description = "Password does not match, or too many wrong passwords")
    @DeleteMapping("/me")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> deleteOwnAccount(@RequestBody(required = false) final DeleteAccountDto deleteAccountDto) {
        userService.deleteOwnAccount(deleteAccountDto != null ? deleteAccountDto : new DeleteAccountDto());

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Export own data", description = "Download all personal data for the authenticated user (profile, URLs, API keys, emails) as JSON.")
    @GetMapping("/me/export")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<DataExportDto> exportMyData() {
        return ResponseEntity.ok(userService.exportMyData());
    }

    @Operation(summary = "Update password", description = "Update the password for the authenticated user.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "403", description = "Password does not match, or too many wrong passwords")
    @PatchMapping("/password")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<User> updatePassword(@Valid @RequestBody final UpdatePasswordDto updatePasswordDto) {
        return ResponseEntity.ok(userService.updatePassword(updatePasswordDto));
    }

}
