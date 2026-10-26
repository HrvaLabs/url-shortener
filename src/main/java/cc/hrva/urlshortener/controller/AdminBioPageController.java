package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.bio.AdminBioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioHandleRevokeDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.AdminBioPageService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/bio-pages")
public class AdminBioPageController {

    private final AdminBioPageService adminBioPageService;

    @Operation(summary = "Search bio pages", description = "Newest first. Searches the handle, the display name and the owner's email; hidden=true keeps only hidden pages.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<AdminBioPageResponse>> fetchBioPages(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "search", required = false) final String search,
            @RequestParam(name = "hidden", defaultValue = "false") final boolean hidden) {
        return ResponseEntity.ok(adminBioPageService.search(search, hidden, pageable));
    }

    @Operation(summary = "Hide a bio page", description = "Takes the page off hrva.cc/@handle with a reason the owner sees, and resolves its open reviews.")
    @PostMapping("/{id}/hide")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.HIDE_BIO_PAGE)
    public ResponseEntity<AdminBioPageResponse> hideBioPage(@PathVariable("id") final long id,
                                                            @Valid @RequestBody final BioPageReasonDto bioPageReasonDto) {
        return ResponseEntity.ok(adminBioPageService.hide(id, bioPageReasonDto));
    }

    @Operation(summary = "Unhide a bio page", description = "Puts a hidden page back and clears the reason.")
    @PostMapping("/{id}/unhide")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.UNHIDE_BIO_PAGE)
    public ResponseEntity<AdminBioPageResponse> unhideBioPage(@PathVariable("id") final long id) {
        return ResponseEntity.ok(adminBioPageService.unhide(id));
    }

    @Operation(summary = "Change a bio page's handle", description = "Follows the owner's handle rules without the rate limit; the old handle is held for the page for 30 days.")
    @PutMapping("/{id}/handle")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.RENAME_BIO_HANDLE)
    public ResponseEntity<AdminBioPageResponse> changeBioPageHandle(@PathVariable("id") final long id,
                                                                    @RequestBody final BioHandleDto bioHandleDto) {
        return ResponseEntity.ok(adminBioPageService.changeHandle(id, bioHandleDto));
    }

    @Operation(summary = "Revoke a bio page's handle", description = "Clears the handle, keeps the page and reserves the old handle on the Reserved aliases page. "
            + "With expectedHandle, answers 409 when the handle is no longer that one.")
    @PostMapping("/{id}/revoke-handle")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.REVOKE_BIO_HANDLE)
    public ResponseEntity<AdminBioPageResponse> revokeBioPageHandle(@PathVariable("id") final long id,
                                                                    @Valid @RequestBody final BioHandleRevokeDto bioHandleRevokeDto) {
        return ResponseEntity.ok(adminBioPageService.revokeHandle(id, bioHandleRevokeDto));
    }

}
