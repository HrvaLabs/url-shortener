package cc.hrva.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

public record AnalyticsOverviewDto(
        int days,
        LocalDate from,
        LocalDate to,
        AnalyticsTotals totals,
        List<AnalyticsDay> daily,
        List<TopLink> topLinks,
        long totalUrls,
        long activeUrls,
        long expiredUrls,
        long totalVisits) {

    public record TopLink(Long id, String shortUrl, String destinationHost, long clicks, long uniqueVisitors,
                          @JsonInclude(JsonInclude.Include.NON_NULL) DomainRef domain) {}

}
