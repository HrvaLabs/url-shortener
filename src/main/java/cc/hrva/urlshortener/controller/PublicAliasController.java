package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.AliasCheckResponse;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.alias.AliasRules;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public")
public class PublicAliasController {

    private static final String RATE_LIMIT_NAME = "alias-check";
    private static final int REQUESTS_PER_CLIENT = 120;
    private static final Duration REQUEST_WINDOW = Duration.ofHours(1);
    private static final String TOO_MANY_REQUESTS = "You've checked a lot of names in a short time. Try again in an hour.";

    private final AliasRules aliasRules;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "Check a custom alias", description = "Says whether a name is free on hrva.cc, taken, reserved, invalid or switched off. "
            + "Nothing about the link that holds a taken name. 120 requests per hour per client.")
    @ApiResponse(responseCode = "200", description = "The status of the name")
    @ApiResponse(responseCode = "429", description = "Too many requests from this client")
    @GetMapping("/alias-check")
    public ResponseEntity<AliasCheckResponse> aliasCheck(@RequestParam(required = false) final String name, final HttpServletRequest request) {
        final var limitKey = RateLimiter.clientKey(RATE_LIMIT_NAME, clientIpResolver.getClientIp(request));
        if (!requestAllowed(limitKey)) {
            throw new RateLimitExceededException(TOO_MANY_REQUESTS, rateLimiter.resetsIn(limitKey).orElse(REQUEST_WINDOW));
        }

        final var trimmed = StringUtils.trimToEmpty(name);
        final var decision = aliasRules.decide(trimmed, null, false);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AliasCheckResponse(trimmed, decision.status(), decision.reason()));
    }

    private boolean requestAllowed(final String limitKey) {
        try {
            return rateLimiter.tryAcquire(limitKey, REQUESTS_PER_CLIENT, REQUEST_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Alias check rate limit unavailable, allowing the request");
            return true;
        }
    }

}
