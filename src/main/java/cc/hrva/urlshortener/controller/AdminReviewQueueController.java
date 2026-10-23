package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.ReviewBulkResolveDto;
import cc.hrva.urlshortener.dto.ReviewBulkResolveResponse;
import cc.hrva.urlshortener.dto.ReviewEntryResponse;
import cc.hrva.urlshortener.dto.ReviewResolveDto;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
@RequestMapping("api/v1/admin/review-queue")
public class AdminReviewQueueController {

    private static final String VIA = "admin";

    private final ReviewQueueService reviewQueueService;
    private final ModerationService moderationService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<ReviewEntryResponse>> fetchQueue(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "reason", required = false) final String reason,
            @RequestParam(name = "olderThanDays", required = false) final Integer olderThanDays) {
        return ResponseEntity.ok(reviewQueueService.openEntries(pageable, reason, olderThanDays));
    }

    @PostMapping("/{urlId}/resolve")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Void> resolve(@PathVariable("urlId") final long urlId, @Valid @RequestBody final ReviewResolveDto reviewResolveDto,
                                        final Authentication authentication) {
        moderationService.resolveReview(urlId, reviewResolveDto.action(), reviewResolveDto.domain(), authentication.getName(), VIA);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resolve")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ReviewBulkResolveResponse> resolveAll(@Valid @RequestBody final ReviewBulkResolveDto reviewBulkResolveDto,
                                                                final Authentication authentication) {
        return ResponseEntity.ok(moderationService.resolveReviews(reviewBulkResolveDto.urlIds(), reviewBulkResolveDto.action(),
                authentication.getName(), VIA));
    }

}
