package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.PublicStatsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public")
public class PublicStatsController {

    private static final String RATE_LIMIT_NAME = "public-stats";
    private static final int REQUESTS_PER_CLIENT = 60;
    private static final Duration REQUEST_WINDOW = Duration.ofHours(1);
    private static final Duration BROWSER_CACHE = Duration.ofMinutes(10);
    private static final String TOO_MANY_REQUESTS = "You've opened these numbers a lot in a short time. Try again in an hour.";

    private final PublicStatsService publicStatsService;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "Site-wide numbers", description = "Aggregates for the last 30 full days. Nothing per link, user, referrer or country. "
            + "60 requests per hour per client.")
    @ApiResponse(responseCode = "200", description = "The site-wide numbers")
    @ApiResponse(responseCode = "429", description = "Too many requests from this client")
    @GetMapping("/stats")
    public ResponseEntity<PublicStatsResponse> publicStats(final HttpServletRequest request) {
        if (!requestAllowed(request)) {
            throw new RateLimitExceededException(TOO_MANY_REQUESTS);
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(BROWSER_CACHE).cachePublic())
                .body(publicStatsService.stats());
    }

    private boolean requestAllowed(final HttpServletRequest request) {
        try {
            return rateLimiter.tryAcquire(RateLimiter.clientKey(RATE_LIMIT_NAME, clientIpResolver.getClientIp(request)), REQUESTS_PER_CLIENT, REQUEST_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Public stats rate limit unavailable, allowing the request");
            return true;
        }
    }

}
