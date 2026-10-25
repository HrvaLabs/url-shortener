package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse.TopLink;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository.TopLinkRow;
import cc.hrva.urlshortener.service.analytics.AnalyticsSeries;
import cc.hrva.urlshortener.util.DomainNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrafficStats {

    private static final int TOP_LINKS_LIMIT = 10;

    private final TrafficStatsRepository repository;

    public TrafficStatsResponse collect(final StatsWindow window) {
        final var stored = repository.findDaily(window.from(), window.to());
        final var ownership = repository.findClicksByOwnership(window.from(), window.to());
        final var topLinks = repository.findTopLinks(window.from(), window.to(), TOP_LINKS_LIMIT).stream()
                .map(TrafficStats::topLinkOf)
                .toList();
        final var breakdowns = repository.findBreakdownTotals(window.from(), window.to());

        return new TrafficStatsResponse(
                window.days(),
                window.from(),
                window.to(),
                AnalyticsSeries.totalsOf(stored),
                AnalyticsSeries.zeroFilled(stored, window.from(), window.to()),
                ownership.anonymous(),
                ownership.account(),
                topLinks,
                AnalyticsSeries.breakdownsOf(breakdowns),
                repository.sumAllTimeVisits());
    }

    private static TopLink topLinkOf(final TopLinkRow row) {
        return new TopLink(
                row.urlId(),
                row.shortUrl(),
                DomainNames.hostOf(row.longUrl()).orElse(""),
                row.ownerEmail(),
                row.clicks(),
                row.uniqueVisitors());
    }

}
