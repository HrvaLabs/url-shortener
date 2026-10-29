package cc.hrva.urlshortener.dto.stats;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record LinksStatsResponse(
        int days,
        LocalDate from,
        LocalDate to,
        StackedSeries created,
        CreatedInRange createdInRange,
        LinkInventory now,
        List<CountEntry> topTags,
        List<CountEntry> topDomains,
        List<NewestLink> newest,
        DeadDestinationCounts deadDestinations) {

    public record CreatedInRange(long total, long account, long anonymous, long aiTagged) {}

    public record LinkInventory(
            long total,
            long active,
            long inactive,
            long expired,
            long passwordProtected,
            long withVisitLimit,
            long withExpiry,
            List<CountEntry> blockedByReason) {}

    public record DeadDestinationCounts(long total, long anonymous) {}

    public record NewestLink(
            Long id,
            String shortUrl,
            String destinationHost,
            String ownerEmail,
            String createdVia,
            LocalDateTime createDate,
            String status) {}

}
