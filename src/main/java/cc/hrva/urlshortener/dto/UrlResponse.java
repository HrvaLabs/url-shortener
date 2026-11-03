package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.domain.UrlCacheKeys;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public record UrlResponse(
        @Schema(description = "Unique identifier", example = "1")
        Long id,
        @Schema(description = "The original long URL", example = "https://example.com/very-long-url")
        String longUrl,
        @Schema(description = "Short URL alias", example = "abc123")
        String shortUrl,
        @Schema(description = "Creation date and time", example = "2026-05-16T10:00:00Z")
        LocalDateTime createDate,
        @Schema(description = "Expiration date and time", example = "2026-12-31T23:59:59Z")
        LocalDateTime expirationDate,
        @Schema(description = "Number of visits", example = "42")
        Long visits,
        @Schema(description = "How many clicks the link allows: every click counts, not counting bots and HEAD requests", example = "100")
        Long visitLimit,
        @Schema(description = "How many clicks have counted towards the visit limit", example = "12")
        Long opens,
        @Schema(description = "Whether the URL is active", example = "true")
        boolean active,
        @Schema(description = "Reason for the current state: ACTIVE, LIMIT_REACHED, EXPIRED, BLOCKED, DEACTIVATED", example = "ACTIVE")
        String status,
        @Schema(description = "Tags assigned to the URL", example = "[\"work\"]")
        Set<String> tags,
        @Schema(description = "Whether the URL requires a password to resolve", example = "false")
        boolean passwordProtected,
        @Schema(description = "Email of the URL owner", example = "user@example.com")
        String ownerEmail,
        @Schema(description = "Why the destination looks like phishing or spam (admin list only)", example = "[\"imitates njuskalo\"]")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> suspiciousReasons,
        @Schema(description = "Why the link is blocked (admin list only)", example = "BLOCKLIST")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BlockReason blockReason,
        @Schema(description = "Whether the link waits in the review queue (admin list only)", example = "false")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean reviewOpen,
        @Schema(description = "Whether the owner looks like a possible scammer (admin list only)", example = "false")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean ownerPossibleScammer,
        @Schema(description = "Where the link's redirect chain ends (admin list only)", example = "https://example.com/landing")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String finalUrl,
        @Schema(description = "Set only while the nightly check finds the destination dead; never in the public view")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        DestinationHealth destinationHealth,
        @Schema(description = "The custom domain the link lives on; absent for hrva.cc links")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        DomainRef domain,
        @Schema(description = "Whether a click opens the warning page first (links made without an account to young or random-looking domains)")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean warningPage,
        @Schema(description = "Whether the warning page is there because the link waits for review; resolving the review as OK removes it")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean warningPageForReview,
        @Schema(description = "The full short link; set in the answer to a create", example = "https://hrva.cc/abc123")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String shortLink) {

    private static final String ACTIVE = "ACTIVE";

    public UrlResponse {
        suspiciousReasons = suspiciousReasons == null ? List.of() : suspiciousReasons;
    }

    public UrlResponse(final Long id, final String longUrl, final String shortUrl, final LocalDateTime createDate,
                       final LocalDateTime expirationDate, final Long visits, final Long visitLimit, final boolean active,
                       final String status, final Set<String> tags, final boolean passwordProtected, final String ownerEmail) {
        this(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, null, active, status, tags, passwordProtected,
                ownerEmail, List.of(), null, null, null, null, null, null, null, null, null);
    }

    public UrlResponse publicView() {
        return new UrlResponse(id, passwordProtected ? null : longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, opens,
                active, status, Set.of(), passwordProtected, null, List.of(), null, null, null, null, null, domain, null, null, null);
    }

    public UrlResponse withSuspiciousReasons(final List<String> reasons) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, opens, active, status, tags,
                passwordProtected, ownerEmail, reasons, blockReason, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain, warningPage,
                warningPageForReview, shortLink);
    }

    public UrlResponse withBlockReason(final BlockReason reason) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, opens, active, status, tags,
                passwordProtected, ownerEmail, suspiciousReasons, reason, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain, warningPage,
                warningPageForReview, shortLink);
    }

    public UrlResponse withModeration(final boolean inReview, final boolean ownerLooksLikeAScammer, final String chainEnd) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, opens, active, status, tags,
                passwordProtected, ownerEmail, suspiciousReasons, blockReason, inReview, ownerLooksLikeAScammer, chainEnd, destinationHealth, domain,
                warningPage, warningPageForReview, shortLink);
    }

    public UrlResponse asCreated() {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, opens, true, ACTIVE, tags, passwordProtected,
                ownerEmail, suspiciousReasons, null, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain, null, null, shortLink);
    }

    public UrlResponse withShortLink(final String link) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, opens, active, status, tags,
                passwordProtected, ownerEmail, suspiciousReasons, blockReason, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain,
                warningPage, warningPageForReview, link);
    }

    @JsonIgnore
    public String hostname() {
        return domain == null ? null : domain.hostname();
    }

    public static UrlResponse from(final Url url) {
        return new UrlResponse(
                url.getId(),
                url.getLongUrl(),
                url.getShortUrl(),
                url.getCreateDate(),
                url.getExpirationDate(),
                url.getVisits(),
                url.getVisitLimit(),
                url.getOpens(),
                url.isActive(),
                resolveStatus(url),
                url.getTags() != null ? url.getTags() : java.util.Set.of(),
                url.getPasswordHash() != null,
                url.getOwner() != null ? url.getOwner().getEmail() : null,
                List.of(),
                null,
                null,
                null,
                null,
                DestinationHealth.of(url),
                DomainRef.of(url.getDomain()),
                url.isWarningPage() ? Boolean.TRUE : null,
                url.isWarningPageForReview() ? Boolean.TRUE : null,
                null);
    }

    @JsonIgnore
    public String cacheKey() {
        return UrlCacheKeys.of(domain == null ? null : domain.id(), shortUrl);
    }

    private static String resolveStatus(final Url url) {
        if (url.isActive()) {
            return ACTIVE;
        }
        if (url.getBlockReason() != null || url.getThreatType() != null) {
            return "BLOCKED";
        }
        if (url.getVisitLimit() != null && url.getVisitLimit() > 0 && url.getOpens() >= url.getVisitLimit()) {
            return "LIMIT_REACHED";
        }
        if (url.getExpirationDate() != null && url.getExpirationDate().isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "DEACTIVATED";
    }

}
