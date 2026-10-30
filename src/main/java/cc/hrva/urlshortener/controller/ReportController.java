package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkReportDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.bio.BioHandles;
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
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
    private static final String HANDLE_PREFIX = "@";
    private static final String BIO_PAGE_PATH = "bio/";
    private static final String TARGET_INVALID = "Enter a hrva.cc short link or bio page, like hrva.cc/abc or hrva.cc/@name";
    private static final String CATEGORY_INVALID = "Category must be PHISHING, MALWARE, SPAM, IMPERSONATION or OTHER";
    private static final String DETAILS_TOO_LONG = "Details must be 500 characters or fewer";
    private static final String TOO_MANY_REPORTS = "You've sent a lot of reports in a short time. Try again in an hour.";
    private static final String BIO_PAGE_REVIEW_PAGE_CONSTRAINT = "fk_bio_page_review_page";

    private final ReviewQueueService reviewQueueService;
    private final UrlRepository urlRepository;
    private final BioPageRepository bioPageRepository;
    private final BioPageReviewService bioPageReviewService;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final HttpServletRequest request;
    private final AppProperties appProperties;

    @Operation(summary = "Report a link or bio page", description = "Report an abusive short link (abc, hrva.cc/abc) or bio page (@name, hrva.cc/@name, app.hrva.cc/bio/name) for review. Always answers 202 for a well-formed report, whether or not the link or page exists. 5 reports per hour per client, shared by links and bio pages.")
    @ApiResponse(responseCode = "202", description = "Report received")
    @ApiResponse(responseCode = "400", description = "Invalid short link or bio page, category or details")
    @ApiResponse(responseCode = "429", description = "Too many reports from this client")
    @PostMapping
    public ResponseEntity<Void> reportLink(@RequestBody final LinkReportDto linkReportDto) {
        final var target = targetOf(linkReportDto.getShortUrl());
        final var category = categoryOf(linkReportDto.getCategory());
        final var details = detailsOf(linkReportDto.getDetails());

        if (!reportAllowed()) {
            throw new RateLimitExceededException(TOO_MANY_REPORTS);
        }

        if (target.handle() != null) {
            bioPageRepository.findByHandleWithOwner(target.handle())
                    .filter(BioPage::isPublic)
                    .ifPresent(page -> reportBioPage(page, category, details));
        } else {
            urlRepository.findActive(null, target.shortCode()).ifPresent(url -> reviewQueueService.report(url, category, details));
        }

        return ResponseEntity.accepted().build();
    }

    private void reportBioPage(final BioPage page, final ReportCategory category, final String details) {
        try {
            bioPageReviewService.report(page, category, details);
        } catch (final DataIntegrityViolationException exception) {
            if (!violates(exception, BIO_PAGE_REVIEW_PAGE_CONSTRAINT)) {
                throw exception;
            }
            logDroppedReport(page);
        } catch (final OptimisticLockingFailureException exception) {
            if (bioPageRepository.existsById(page.getId())) {
                throw exception;
            }
            logDroppedReport(page);
        }
    }

    private static void logDroppedReport(final BioPage page) {
        log.info("Visitor report dropped because the bio page was deleted meanwhile pageId={}", page.getId());
    }

    private boolean reportAllowed() {
        try {
            return rateLimiter.tryAcquire(RateLimiter.clientKey(RATE_LIMIT_NAME, clientIpResolver.getClientIp(request)), REPORTS_PER_CLIENT, REPORT_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Report rate limit unavailable, allowing the report");
            return true;
        }
    }

    private ReportTarget targetOf(final String reported) {
        final var link = StringUtils.removeStartIgnoreCase(withoutScheme(StringUtils.defaultString(reported).strip()), "www.");
        final var shortHost = hostOf(appProperties.getServerUrl());
        final var frontendHost = hostOf(appProperties.getFrontendUrl());
        if (StringUtils.startsWithIgnoreCase(link, shortHost + "/")) {
            return shortLinkOrHandle(pathOf(link.substring(shortHost.length())));
        }
        if (!frontendHost.isEmpty() && StringUtils.startsWithIgnoreCase(link, frontendHost + "/")) {
            return bioPagePath(pathOf(link.substring(frontendHost.length())));
        }
        return shortLinkOrHandle(pathOf(link));
    }

    private static ReportTarget shortLinkOrHandle(final String path) {
        if (path.startsWith(HANDLE_PREFIX)) {
            return handleOf(path);
        }
        return Optional.of(path)
                .filter(code -> SHORT_CODE.matcher(code).matches())
                .map(code -> new ReportTarget(code, null))
                .orElseThrow(() -> new ApiException(TARGET_INVALID));
    }

    private static ReportTarget bioPagePath(final String path) {
        if (!path.startsWith(BIO_PAGE_PATH)) {
            throw new ApiException(TARGET_INVALID);
        }
        return handleOf(path.substring(BIO_PAGE_PATH.length()));
    }

    private static ReportTarget handleOf(final String raw) {
        final var handle = BioHandles.normalize(raw);
        if (BioHandles.formatError(handle).isPresent()) {
            throw new ApiException(TARGET_INVALID);
        }
        return new ReportTarget(null, handle);
    }

    private static String pathOf(final String link) {
        return StringUtils.strip(StringUtils.substringBefore(StringUtils.substringBefore(link, "?"), "#"), "/");
    }

    private static String hostOf(final String url) {
        return StringUtils.removeEnd(withoutScheme(StringUtils.defaultString(url)), "/");
    }

    private static String withoutScheme(final String link) {
        return SCHEME.matcher(link).replaceFirst("");
    }

    private static boolean violates(final DataIntegrityViolationException exception, final String constraint) {
        final var violation = ExceptionUtils.throwableOfType(exception, ConstraintViolationException.class);
        return violation != null && StringUtils.containsIgnoreCase(violation.getConstraintName(), constraint);
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

    private record ReportTarget(String shortCode, String handle) {
    }

}
