package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.bio.BioPageReviewEntryResponse;
import cc.hrva.urlshortener.dto.bio.BioPageReviewResolveDto;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.AdminBioPageService;
import cc.hrva.urlshortener.service.BioPageReviewService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/bio-page-reviews")
public class AdminBioPageReviewController {

    private final BioPageReviewService bioPageReviewService;
    private final AdminBioPageService adminBioPageService;

    @Operation(summary = "Open bio page reviews", description = "Newest first unless sorted by flaggedAt, each with the page, its owner and every reason; "
            + "search matches the handle, the display name or the owner's email.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<BioPageReviewEntryResponse>> fetchBioPageReviews(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(bioPageReviewService.openEntries(search, pageable));
    }

    @Operation(summary = "Resolve a bio page review", description = "OK leaves the page up; HIDE hides it with the reason. Closes every open review of the page.")
    @PostMapping("/{pageId}/resolve")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.RESOLVE_BIO_REVIEW)
    public ResponseEntity<Void> resolveBioPageReview(@PathVariable("pageId") final long pageId,
                                                     @Valid @RequestBody final BioPageReviewResolveDto bioPageReviewResolveDto) {
        adminBioPageService.resolveReview(pageId, bioPageReviewResolveDto);

        return ResponseEntity.noContent().build();
    }

}
