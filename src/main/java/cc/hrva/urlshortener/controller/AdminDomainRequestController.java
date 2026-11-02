package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.domain.AdminDomainRequestResponse;
import cc.hrva.urlshortener.dto.domain.DomainReviewDto;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import cc.hrva.urlshortener.service.CustomDomainRequestAdminService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
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
@RequestMapping("api/v1/admin/domains/requests")
public class AdminDomainRequestController {

    private final CustomDomainRequestAdminService customDomainRequestAdminService;

    @Operation(summary = "Custom domain limit requests", description = "Requests for more custom domains, newest first, with the account, its limit "
            + "and the domains it uses. status keeps one status; search matches the account's email or the reason.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<AdminDomainRequestResponse>> fetchRequests(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "status", required = false) final DomainRequestStatus status,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(customDomainRequestAdminService.list(status, search, pageable));
    }

    @Operation(summary = "Approve a custom domain limit request", description = "Sets the account's limit to the requested total, with the request's "
            + "reason, and emails the account with the optional note.")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.APPROVE_DOMAIN_REQUEST)
    public ResponseEntity<AdminDomainRequestResponse> approveRequest(@PathVariable("id") final long id,
                                                                     @Valid @RequestBody(required = false) final DomainReviewDto domainReviewDto) {
        return ResponseEntity.ok(customDomainRequestAdminService.approve(id, noteOf(domainReviewDto), signedInAdmin()));
    }

    @Operation(summary = "Decline a custom domain limit request", description = "Keeps the account's limit and emails the account with the optional note.")
    @PostMapping("/{id}/decline")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.DECLINE_DOMAIN_REQUEST)
    public ResponseEntity<AdminDomainRequestResponse> declineRequest(@PathVariable("id") final long id,
                                                                     @Valid @RequestBody(required = false) final DomainReviewDto domainReviewDto) {
        return ResponseEntity.ok(customDomainRequestAdminService.decline(id, noteOf(domainReviewDto), signedInAdmin()));
    }

    private static String noteOf(final DomainReviewDto domainReviewDto) {
        return domainReviewDto == null ? null : domainReviewDto.getNote();
    }

    private static String signedInAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

}
