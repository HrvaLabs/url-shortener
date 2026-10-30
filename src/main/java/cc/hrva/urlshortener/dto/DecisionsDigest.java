package cc.hrva.urlshortener.dto;

import java.util.List;

public record DecisionsDigest(
        long openCount,
        long reportedCount,
        List<LinkDigestEntry> newestOpen,
        List<PossibleScammer> possibleScammers,
        List<BlockedLink> autoBlocked,
        List<FailingSource> failingSources,
        List<NewAccountLink> newAccountLinks,
        long openBioPageReviews,
        List<BusyAnonymousLink> busiestAnonymous) {

    public record PossibleScammer(String email, long blockedLinks, long flaggedLinks) {}

    public record BlockedLink(String shortUrl, String destination, String reason, String domain) {}

    public record FailingSource(String name, String lastError) {}

    public record NewAccountLink(String shortUrl, String destination, String ownerEmail, String domain) {}

    public record BusyAnonymousLink(String shortUrl, String destinationHost, long clicks) {}

}
