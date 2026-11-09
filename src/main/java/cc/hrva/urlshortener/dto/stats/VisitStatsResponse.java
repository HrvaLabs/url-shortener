package cc.hrva.urlshortener.dto.stats;

import java.time.LocalDate;
import java.util.List;

public record VisitStatsResponse(boolean connected, int days, int requestedDays, LocalDate from, LocalDate to, long total,
                                 List<Source> sources, List<Page> pages) {

    public VisitStatsResponse requestedAs(final int requested) {
        return new VisitStatsResponse(connected, days, requested, from, to, total, sources, pages);
    }

    public record Source(String source, String kind, long visits) {
    }

    public record Page(String page, long visits) {
    }

}
