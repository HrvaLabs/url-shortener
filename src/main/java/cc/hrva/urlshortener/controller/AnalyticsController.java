package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.service.AnalyticsService;
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

    @Operation(summary = "Analytics overview", description = "Aggregate analytics across the authenticated user's URLs.")
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<AnalyticsOverviewDto> getOverview() {
        return ResponseEntity.ok(analyticsService.getOverview());
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

}
