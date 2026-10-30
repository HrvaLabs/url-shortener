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
        @Schema(description = "Creation date and time", example = "2026-05-16T10:00:00")
        LocalDateTime createDate,
        @Schema(description = "Expiration date and time", example = "2026-12-31T23:59:59")
        LocalDateTime expirationDate,
        @Schema(description = "Number of visits", example = "42")
        Long visits,
        @Schema(description = "Maximum number of visits allowed", example = "100")
        Long visitLimit,
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
        DomainRef domain) {

    private static final String ACTIVE = "ACTIVE";

    public UrlResponse {
        suspiciousReasons = suspiciousReasons == null ? List.of() : suspiciousReasons;
    }

    public UrlResponse(final Long id, final String longUrl, final String shortUrl, final LocalDateTime createDate,
                       final LocalDateTime expirationDate, final Long visits, final Long visitLimit, final boolean active,
                       final String status, final Set<String> tags, final boolean passwordProtected, final String ownerEmail) {
        this(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, active, status, tags, passwordProtected,
                ownerEmail, List.of(), null, null, null, null, null, null);
    }

    public UrlResponse publicView() {
        return new UrlResponse(id, passwordProtected ? null : longUrl, shortUrl, createDate, expirationDate, visits, visitLimit,
                active, status, Set.of(), passwordProtected, null, List.of(), null, null, null, null, null, domain);
    }

    public UrlResponse withSuspiciousReasons(final List<String> reasons) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, active, status, tags,
                passwordProtected, ownerEmail, reasons, blockReason, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain);
    }

    public UrlResponse withBlockReason(final BlockReason reason) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, active, status, tags,
                passwordProtected, ownerEmail, suspiciousReasons, reason, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain);
    }

    public UrlResponse withModeration(final boolean inReview, final boolean ownerLooksLikeAScammer, final String chainEnd) {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, active, status, tags,
                passwordProtected, ownerEmail, suspiciousReasons, blockReason, inReview, ownerLooksLikeAScammer, chainEnd, destinationHealth, domain);
    }

    public UrlResponse asCreated() {
        return new UrlResponse(id, longUrl, shortUrl, createDate, expirationDate, visits, visitLimit, true, ACTIVE, tags, passwordProtected,
                ownerEmail, suspiciousReasons, null, reviewOpen, ownerPossibleScammer, finalUrl, destinationHealth, domain);
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
                DomainRef.of(url.getDomain()));
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
        if (url.getVisitLimit() != null && url.getVisits() != null
                && url.getVisits() >= url.getVisitLimit()) {
            return "LIMIT_REACHED";
        }
        if (url.getExpirationDate() != null && url.getExpirationDate().isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "DEACTIVATED";
    }

}
