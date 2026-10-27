package cc.hrva.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "Generate a new API key", description = "Generate a new API key for the authenticated user. Requires ROLE_USER.")
    @PostMapping
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<ApiKeyResponse> generateNewApiKey() {
        return ResponseEntity.ok(apiKeyService.generateNewApiKey());
    }

    @Operation(summary = "Get my API keys", description = "Retrieve all API keys belonging to the authenticated user. Requires ROLE_USER.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<List<ApiKeyResponse>> fetchMyApiKeys() {
        return ResponseEntity.ok(apiKeyService.fetchMyApiKeys());
    }

    @io.swagger.v3.oas.annotations.Hidden
    @Operation(summary = "Get all API keys (admin)", description = "Retrieve paginated list of all API keys in the system. Requires ROLE_ADMIN.")
    @GetMapping("/all")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<ApiKeyResponse>> fetchAllApiKeys(
            @PageableDefault(size = 20) final Pageable pageable) {
        return ResponseEntity.ok(apiKeyService.fetchAllApiKeys(pageable));
    }

    @io.swagger.v3.oas.annotations.Hidden
    @Operation(summary = "Update an API key", description = "Update API key settings. Requires ROLE_ADMIN.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "404", description = "API key not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ApiKeyResponse> updateApiKey(@PathVariable final Long id, @Valid @RequestBody final ApiKeyUpdateDto apiKeyUpdateDto) {
        apiKeyUpdateDto.setId(id);
        return ResponseEntity.ok(apiKeyService.updateKey(apiKeyUpdateDto));
    }

    @Operation(summary = "Revoke an API key", description = "Revoke (deactivate) an API key by its ID. Authentication required.")
    @ApiResponse(responseCode = "404", description = "API key not found")
    @PatchMapping("/{id}/revoke")
    public ResponseEntity<ApiKeyResponse> revokeApiKey(@PathVariable("id") final Long id) {
        return ResponseEntity.ok(apiKeyService.revokeApiKey(id));
    }

    @Operation(summary = "Activate an API key", description = "Reactivate a previously revoked API key by its ID. Authentication required.")
    @ApiResponse(responseCode = "404", description = "API key not found")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<ApiKeyResponse> activateApiKey(@PathVariable("id") final Long id) {
        return ResponseEntity.ok(apiKeyService.activateApiKey(id));
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
