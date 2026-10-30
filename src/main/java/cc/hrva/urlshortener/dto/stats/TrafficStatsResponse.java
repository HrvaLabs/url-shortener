package cc.hrva.urlshortener.dto.stats;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

public record TrafficStatsResponse(
        int days,
        LocalDate from,
        LocalDate to,
        AnalyticsTotals totals,
        List<AnalyticsDay> daily,
        long anonymousClicks,
        long accountClicks,
        List<TopLink> topLinks,
        UrlAnalyticsDto.Breakdowns breakdowns,
        long allTimeVisits,
        BioPageViewTotals bioPageViews) {

    public record TopLink(Long id, String shortUrl, String destinationHost, String ownerEmail, long clicks, long uniqueVisitors,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String domain) {}

    public record BioPageViewTotals(long views, long botViews) {}

}
