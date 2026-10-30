package cc.hrva.urlshortener.dto.notification;

import java.time.LocalDate;
import java.util.List;

public record DailySummary(LocalDate date, long clicks, long uniqueVisitors, long botClicks, List<SummaryLink> links) {

    public DailySummary {
        links = List.copyOf(links);
    }

    public record SummaryLink(String shortUrl, String destinationHost, long clicks, long uniqueVisitors, long botClicks, String domain) {
    }

}
