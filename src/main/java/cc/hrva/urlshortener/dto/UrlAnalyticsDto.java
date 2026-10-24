package cc.hrva.urlshortener.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record UrlAnalyticsDto(
        LinkSummary link,
        int days,
        LocalDate from,
        LocalDate to,
        AnalyticsTotals totals,
        List<AnalyticsDay> daily,
        Breakdowns breakdowns) {

    public record LinkSummary(
            Long id,
            String shortUrl,
            String destinationHost,
            String status,
            boolean passwordProtected,
            LocalDateTime createDate,
            LocalDateTime lastAccessed,
            long visits) {}

    public record Breakdowns(
            List<BreakdownEntry> referrers,
            List<BreakdownEntry> countries,
            List<BreakdownEntry> devices,
            List<BreakdownEntry> browsers,
            List<BreakdownEntry> operatingSystems) {}

}
