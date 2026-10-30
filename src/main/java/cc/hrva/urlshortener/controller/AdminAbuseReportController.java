package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.abuse.AbuseReportRequest;
import cc.hrva.urlshortener.dto.abuse.AbuseReportResponse;
import cc.hrva.urlshortener.dto.abuse.ReportedDomainsDto;
import cc.hrva.urlshortener.service.AbuseReportService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/abuse-reports")
public class AdminAbuseReportController {

    private final AbuseReportService abuseReportService;

    @PostMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<AbuseReportResponse> prepare(@Valid @RequestBody final AbuseReportRequest request) {
        return ResponseEntity.ok(abuseReportService.prepare(request));
    }

    @PostMapping("/reported")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Void> markReported(@Valid @RequestBody final ReportedDomainsDto request, final Authentication authentication) {
        abuseReportService.markReported(request, authentication.getName());
        return ResponseEntity.noContent().build();
    }

}
