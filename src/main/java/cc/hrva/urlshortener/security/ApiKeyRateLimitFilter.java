package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimitWindow;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ApiKeyRateLimitFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String LIMIT_HEADER = "RateLimit-Limit";
    public static final String REMAINING_HEADER = "RateLimit-Remaining";
    public static final String RESET_HEADER = "RateLimit-Reset";
    public static final List<String> EXPOSED_HEADERS = List.of(LIMIT_HEADER, REMAINING_HEADER, RESET_HEADER, HttpHeaders.RETRY_AFTER);

    private static final String LINK_API = "/api/v1/urls";
    private static final String RATE_LIMIT_NAME = "api-key:";
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);
    private static final String REFUSAL = "{\"error\":\"This API key made too many requests. Try again in %s.\"}";

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimiter rateLimiter;
    private final SettingsService settingsService;
    private final Clock clock;
    private final AtomicReference<Instant> nextWarningAt = new AtomicReference<>(Instant.MIN);

    public ApiKeyRateLimitFilter(final ApiKeyRepository apiKeyRepository, final RateLimiter rateLimiter, final SettingsService settingsService,
            final Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimiter = rateLimiter;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final var path = StringUtils.removeStart(request.getRequestURI(), request.getContextPath());
        final var linkApi = LINK_API.equals(path) || StringUtils.startsWith(path, LINK_API + "/");
        return !linkApi || StringUtils.isBlank(request.getHeader(API_KEY_HEADER));
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        final var keyId = apiKeyRepository.findIdByKeyHash(ApiKeyTokens.hash(request.getHeader(API_KEY_HEADER)));
        if (keyId.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        final RateLimitWindow window;
        try {
            window = rateLimiter.incrementWindow(RATE_LIMIT_NAME + keyId.get(), WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            warnAtMostOncePerInterval();
            filterChain.doFilter(request, response);
            return;
        }

        final var limit = settingsService.number(SettingKey.API_KEY_REQUESTS_PER_HOUR);
        final var resetSeconds = Math.max(1, (window.resetsIn().toMillis() + 999) / 1000);
        response.setHeader(LIMIT_HEADER, Integer.toString(limit));
        response.setHeader(REMAINING_HEADER, Long.toString(Math.max(0, limit - window.count())));
        response.setHeader(RESET_HEADER, Long.toString(resetSeconds));

        if (window.count() > limit) {
            log.info("API key request refused by the rate limit apiKeyId={}", keyId.get());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(resetSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(REFUSAL.formatted(minutesOf(resetSeconds)));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String minutesOf(final long seconds) {
        final var minutes = Math.max(1, (seconds + 59) / 60);
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    private void warnAtMostOncePerInterval() {
        final var now = clock.instant();
        final var due = nextWarningAt.get();
        if (!now.isBefore(due) && nextWarningAt.compareAndSet(due, now.plus(WARNING_INTERVAL))) {
            log.warn("API key rate limit unavailable, allowing the request");
        }
    }

}
