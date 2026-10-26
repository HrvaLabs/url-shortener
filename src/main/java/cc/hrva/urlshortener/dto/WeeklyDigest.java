package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import java.time.LocalDate;
import java.util.List;

public record WeeklyDigest(
        LocalDate from,
        LocalDate to,
        Change clicks,
        long uniqueVisitors,
        long botClicks,
        Change newLinks,
        List<CountEntry> linksByChannel,
        Change newUsers,
        long activeUsers,
        long reviewsOpened,
        long reviewsResolved,
        List<CountEntry> automaticBlocks,
        List<TopLink> topLinks) {

    public record Change(long current, long previous) {}

    public record TopLink(String shortUrl, String destinationHost, long clicks) {}

}
