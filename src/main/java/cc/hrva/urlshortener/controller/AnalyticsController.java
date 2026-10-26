package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.bio.BioPageAnalyticsResponse;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.BioPageAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final BioPageAnalyticsService bioPageAnalyticsService;

    @Operation(summary = "Analytics overview", description = "Analytics across the authenticated user's URLs over the last 7, 30, 90 "
            + "or 365 days (30 by default): range totals, a zero-filled daily series, the top 10 links by clicks and the all-time counters.")
    @ApiResponse(responseCode = "400", description = "days is not 7, 30, 90 or 365")
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<AnalyticsOverviewDto> getOverview(@RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(analyticsService.getOverview(days));
    }

    @Operation(summary = "URL analytics", description = "Analytics for a single URL owned by the authenticated user (or any URL for admins) "
            + "over the last 7, 30, 90 or 365 days (30 by default): the link summary, range totals, a zero-filled daily series "
            + "and the top 10 values plus other for each breakdown.")
    @ApiResponse(responseCode = "400", description = "days is not 7, 30, 90 or 365")
    @ApiResponse(responseCode = "403", description = "Not the owner")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @GetMapping("/urls/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<UrlAnalyticsDto> getUrlAnalytics(
            @PathVariable("id") final Long id,
            @RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(analyticsService.getUrlAnalytics(id, days));
    }

    @Operation(summary = "Bio page analytics", description = "Views of the authenticated user's bio page over the last 7, 30, 90 or 365 days "
            + "(30 by default): range totals and a zero-filled daily series of unique human views and bot views, then every button in page "
            + "order with whether it shows on the page, its clicks from hrva.cc pages and all clicks of its link.")
    @ApiResponse(responseCode = "400", description = "days is not 7, 30, 90 or 365")
    @ApiResponse(responseCode = "404", description = "You don't have a bio page yet.")
    @GetMapping("/bio-page")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<BioPageAnalyticsResponse> getBioPageAnalytics(@RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(bioPageAnalyticsService.analytics(days));
    }

}
