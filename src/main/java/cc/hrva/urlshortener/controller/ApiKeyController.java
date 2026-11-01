package cc.hrva.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.dto.ApiKeyCreateDto;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.dto.CreatedApiKeyResponse;
import cc.hrva.urlshortener.dto.DeletedApiKeysResponse;
import cc.hrva.urlshortener.service.ApiKeyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "Generate a new API key", description = "Generate a new API key for the authenticated user. The body is optional: "
            + "expiresInDays (1 to 3650) or neverExpires: true. Without either the key expires after 12 months. The answer carries the full key once, "
            + "in key; only its hash is stored, and every other answer has keyPrefix only. Requires ROLE_USER.")
    @ApiResponse(responseCode = "400", description = "Days out of range, or both expiresInDays and neverExpires")
    @PostMapping
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<CreatedApiKeyResponse> generateNewApiKey(@Valid @RequestBody(required = false) final ApiKeyCreateDto expiry) {
        return ResponseEntity.ok(apiKeyService.generateNewApiKey(expiry));
    }

    @Operation(summary = "Get my API keys", description = "Retrieve the API keys belonging to the authenticated user, newest first. "
            + "active=true or active=false keeps only those. Requires ROLE_USER.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<List<ApiKeyResponse>> fetchMyApiKeys(@RequestParam(name = "active", required = false) final Boolean active) {
        return ResponseEntity.ok(apiKeyService.fetchMyApiKeys(active));
    }

    @Operation(summary = "Get my inactive API keys", description = "A page of the authenticated user's expired or revoked keys, newest first. "
            + "Requires ROLE_USER.")
    @GetMapping("/inactive")
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<Page<ApiKeyResponse>> fetchMyInactiveApiKeys(@PageableDefault(size = 20) final Pageable pageable) {
        return ResponseEntity.ok(apiKeyService.fetchMyInactiveApiKeys(pageable));
    }

    @io.swagger.v3.oas.annotations.Hidden
    @Operation(summary = "Get all API keys (admin)", description = "Retrieve paginated list of all API keys in the system. Requires ROLE_ADMIN.")
    @GetMapping("/all")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<ApiKeyResponse>> fetchAllApiKeys(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "search", required = false) final String search,
            @RequestParam(name = "active", required = false) final Boolean active) {
        return ResponseEntity.ok(apiKeyService.fetchAllApiKeys(search, active, pageable));
    }

    @Operation(summary = "Update an API key", description = "Change an active key's expiry (expiresInDays 1 to 3650, neverExpires: true, or a future "
            + "expirationDate) or revoke it with active: false. Revoking is final: a revoked or expired key can't be turned back on or changed, only "
            + "deleted. The usage can't be edited. The key's owner or an admin.")
    @ApiResponse(responseCode = "400", description = "Days out of range, a past expirationDate, or more than one expiry")
    @ApiResponse(responseCode = "403", description = "Not the key's owner or an admin")
    @ApiResponse(responseCode = "404", description = "API key not found")
    @ApiResponse(responseCode = "409", description = "The key is revoked or expired: it can only be deleted")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<ApiKeyResponse> updateApiKey(@PathVariable final Long id, @Valid @RequestBody final ApiKeyUpdateDto apiKeyUpdateDto) {
        apiKeyUpdateDto.setId(id);
        return ResponseEntity.ok(apiKeyService.updateKey(apiKeyUpdateDto));
    }

    @Operation(summary = "Revoke an API key", description = "Revoke an API key by its ID. Revoking is final: the key can't be turned back on, only deleted. Authentication required.")
    @ApiResponse(responseCode = "404", description = "API key not found")
    @PatchMapping("/{id}/revoke")
    public ResponseEntity<ApiKeyResponse> revokeApiKey(@PathVariable("id") final Long id) {
        return ResponseEntity.ok(apiKeyService.revokeApiKey(id));
    }

    @Operation(summary = "Delete an inactive API key", description = "Permanently delete an expired or revoked API key by its ID. An active key must be revoked first. The key's owner or an admin.")
    @ApiResponse(responseCode = "204", description = "API key deleted")
    @ApiResponse(responseCode = "403", description = "Not the key's owner or an admin")
    @ApiResponse(responseCode = "404", description = "API key not found")
    @ApiResponse(responseCode = "409", description = "The key is active: revoke it first")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApiKey(@PathVariable("id") final Long id) {
        apiKeyService.deleteApiKey(id);

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete my inactive API keys", description = "Permanently delete all of the authenticated user's expired or revoked API keys and return how many were deleted. Never touches other users' keys, even for an admin.")
    @DeleteMapping("/inactive")
    public ResponseEntity<DeletedApiKeysResponse> deleteMyInactiveApiKeys() {
        return ResponseEntity.ok(new DeletedApiKeysResponse(apiKeyService.deleteMyInactiveApiKeys()));
    }

}
