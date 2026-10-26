package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkReportDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(ReportController.REPORTS_ENDPOINT)
public class ReportController {

    public static final String REPORTS_ENDPOINT = "/api/v1/reports";

    private static final String RATE_LIMIT_NAME = "report";
    private static final int REPORTS_PER_CLIENT = 5;
    private static final Duration REPORT_WINDOW = Duration.ofHours(1);
    private static final int DETAILS_LIMIT = 500;
    private static final Pattern SCHEME = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_CODE = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\s]]");
    private static final String SHORT_LINK_INVALID = "Enter a hrva.cc short link, like hrva.cc/abc";
    private static final String CATEGORY_INVALID = "Category must be PHISHING, MALWARE, SPAM or OTHER";
    private static final String DETAILS_TOO_LONG = "Details must be 500 characters or fewer";
    private static final String TOO_MANY_REPORTS = "You've sent a lot of reports in a short time. Try again in an hour.";

    private final ReviewQueueService reviewQueueService;
    private final UrlRepository urlRepository;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final HttpServletRequest request;
    private final AppProperties appProperties;

    @Operation(summary = "Report a link", description = "Report an abusive short link for review. Always answers 202 for a well-formed report, whether or not the link exists. 5 reports per hour per client.")
    @ApiResponse(responseCode = "202", description = "Report received")
    @ApiResponse(responseCode = "400", description = "Invalid short link, category or details")
    @ApiResponse(responseCode = "429", description = "Too many reports from this client")
    @PostMapping
    public ResponseEntity<Void> reportLink(@RequestBody final LinkReportDto linkReportDto) {
        final var shortUrl = shortCodeOf(linkReportDto.getShortUrl());
        final var category = categoryOf(linkReportDto.getCategory());
        final var details = detailsOf(linkReportDto.getDetails());

        if (!reportAllowed()) {
            throw new RateLimitExceededException(TOO_MANY_REPORTS);
        }

        urlRepository.findByShortUrlAndActiveTrue(shortUrl).ifPresent(url -> reviewQueueService.report(url, category, details));

        return ResponseEntity.accepted().build();
    }

    private boolean reportAllowed() {
        try {
            return rateLimiter.tryAcquire(RateLimiter.clientKey(RATE_LIMIT_NAME, clientIpResolver.getClientIp(request)), REPORTS_PER_CLIENT, REPORT_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Report rate limit unavailable, allowing the report");
            return true;
        }
    }

    private String shortCodeOf(final String shortLink) {
        final var link = StringUtils.removeStartIgnoreCase(withoutScheme(StringUtils.defaultString(shortLink).strip()), "www.");
        final var shortHost = StringUtils.removeEnd(withoutScheme(appProperties.getServerUrl()), "/");
        final var path = StringUtils.startsWithIgnoreCase(link, shortHost + "/") ? link.substring(shortHost.length()) : link;
        final var shortCode = StringUtils.strip(StringUtils.substringBefore(StringUtils.substringBefore(path, "?"), "#"), "/");

        return Optional.of(shortCode)
                .filter(code -> SHORT_CODE.matcher(code).matches())
                .orElseThrow(() -> new ApiException(SHORT_LINK_INVALID));
    }

    private static String withoutScheme(final String link) {
        return SCHEME.matcher(link).replaceFirst("");
    }

    private static ReportCategory categoryOf(final String category) {
        return Optional.ofNullable(EnumUtils.getEnum(ReportCategory.class, category))
                .orElseThrow(() -> new ApiException(CATEGORY_INVALID));
    }

    private static String detailsOf(final String details) {
        if (details == null) {
            return null;
        }

        final var cleaned = StringUtils.normalizeSpace(CONTROL_CHARACTERS.matcher(details).replaceAll(""));
        if (cleaned.length() > DETAILS_LIMIT) {
            throw new ApiException(DETAILS_TOO_LONG);
        }

        return StringUtils.defaultIfEmpty(cleaned, null);
    }

}
