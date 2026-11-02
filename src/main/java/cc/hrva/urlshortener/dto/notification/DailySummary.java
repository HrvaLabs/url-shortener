package cc.hrva.urlshortener.dto.notification;

import java.time.LocalDate;
import java.util.List;

public record DailySummary(LocalDate date, long clicks, long uniqueVisitors, long botClicks, List<SummaryLink> links, long moreLinks) {

    public DailySummary {
        links = List.copyOf(links);
    }

    public DailySummary(final LocalDate date, final long clicks, final long uniqueVisitors, final long botClicks, final List<SummaryLink> links) {
        this(date, clicks, uniqueVisitors, botClicks, links, 0);
    }

    public record SummaryLink(String shortUrl, String destinationHost, long clicks, long uniqueVisitors, long botClicks, String domain) {
    }

}
