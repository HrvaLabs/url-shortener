package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.domain.AdminDomainResponse;
import cc.hrva.urlshortener.dto.domain.CloudflareCheckResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceResponse;
import cc.hrva.urlshortener.dto.domain.DomainReviewDto;
import cc.hrva.urlshortener.dto.domain.DomainSummaryResponse;
import cc.hrva.urlshortener.dto.domain.ReconcileResponse;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.service.CustomDomainAdminService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("api/v1/admin/domains")
public class AdminCustomDomainController {

    private final CustomDomainAdminService customDomainAdminService;

    @Operation(summary = "Custom domains", description = "Every custom domain, newest first, with its owner, links and clicks. "
            + "status keeps one status; search matches the hostname or the owner's email.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<AdminDomainResponse>> fetchDomains(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "status", required = false) final CustomDomainStatus status,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(customDomainAdminService.list(status, search, pageable));
    }

    @Operation(summary = "Custom domain summary", description = "Slots in use against the cap, Cloudflare's total against the free allowance, "
            + "applications waiting, active domains, the last token check and the last reconcile.")
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<DomainSummaryResponse> fetchSummary() {
        return ResponseEntity.ok(customDomainAdminService.summary());
    }

    @Operation(summary = "Approve a custom domain", description = "Checks the Cloudflare token and Cloudflare's live total first, then approves "
            + "within the cap and the free allowance; the hostname is set up in Cloudflare right after.")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.APPROVE_DOMAIN)
    public ResponseEntity<AdminDomainResponse> approveDomain(@PathVariable("id") final long id,
                                                             @Valid @RequestBody(required = false) final DomainReviewDto domainReviewDto) {
        return ResponseEntity.ok(customDomainAdminService.approve(id, noteOf(domainReviewDto), signedInAdmin()));
    }

    @Operation(summary = "Reject a custom domain", description = "Rejects an application with an optional note the owner sees; the hostname "
            + "can be applied for again.")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.REJECT_DOMAIN)
    public ResponseEntity<AdminDomainResponse> rejectDomain(@PathVariable("id") final long id,
                                                            @Valid @RequestBody(required = false) final DomainReviewDto domainReviewDto) {
        return ResponseEntity.ok(customDomainAdminService.reject(id, noteOf(domainReviewDto), signedInAdmin()));
    }

    @Operation(summary = "Retry a custom domain", description = "Sets up again a domain whose Cloudflare setup failed, or asks Cloudflare now "
            + "about a domain that waits for its CNAME record or has a problem.")
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.RETRY_DOMAIN)
    public ResponseEntity<AdminDomainResponse> retryDomain(@PathVariable("id") final long id) {
        return ResponseEntity.ok(customDomainAdminService.retry(id));
    }

    @Operation(summary = "Reconcile with Cloudflare", description = "Compares every Cloudflare custom hostname with ours now and records the run; "
            + "nothing is ever deleted. While a run is going, the last run is answered.")
    @PostMapping("/reconcile")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ReconcileResponse> reconcile() {
        return ResponseEntity.ok(customDomainAdminService.reconcile());
    }

    @Operation(summary = "Check the Cloudflare token", description = "Asks Cloudflare now whether the API token can manage custom hostnames.")
    @PostMapping("/cloudflare-check")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<CloudflareCheckResponse> checkCloudflare() {
        return ResponseEntity.ok(customDomainAdminService.checkCloudflare());
    }

    @Operation(summary = "An account's custom domain limit", description = "The account's own limit, or the default with no reason.")
    @GetMapping("/allowances/{userId}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<DomainAllowanceResponse> fetchAllowance(@PathVariable("userId") final long userId) {
        return ResponseEntity.ok(customDomainAdminService.allowance(userId));
    }

    @Operation(summary = "Set an account's custom domain limit", description = "1 to 20 custom domains, with a reason.")
    @PutMapping("/allowances/{userId}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.SET_DOMAIN_ALLOWANCE)
    public ResponseEntity<DomainAllowanceResponse> setAllowance(@PathVariable("userId") final long userId,
                                                                @Valid @RequestBody final DomainAllowanceDto domainAllowanceDto) {
        return ResponseEntity.ok(customDomainAdminService.setAllowance(userId, domainAllowanceDto, signedInAdmin()));
    }

    @Operation(summary = "Remove an account's custom domain limit", description = "The account goes back to the default limit.")
    @DeleteMapping("/allowances/{userId}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.REMOVE_DOMAIN_ALLOWANCE)
    public ResponseEntity<Void> removeAllowance(@PathVariable("userId") final long userId) {
        customDomainAdminService.removeAllowance(userId);
        return ResponseEntity.noContent().build();
    }

    private static String noteOf(final DomainReviewDto domainReviewDto) {
        return domainReviewDto == null ? null : domainReviewDto.getNote();
    }

    private static String signedInAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

}
