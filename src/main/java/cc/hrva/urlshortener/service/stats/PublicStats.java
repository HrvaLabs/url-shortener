package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.BlockedLinks;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.Blocklist;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.DailyClicks;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.HourlySeries;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository;
import cc.hrva.urlshortener.repository.stats.SafetyStatsRepository;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.AnalyticsSeries;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PublicStats {

    private static final int DAYS = 30;

    private final StatsDays statsDays;
    private final AnalyticsDays analyticsDays;
    private final TrafficStatsRepository trafficStatsRepository;
    private final LinksStatsRepository linksStatsRepository;
    private final HourlyCountRepository hourlyCountRepository;
    private final SafetyStatsRepository safetyStatsRepository;
    private final BlocklistSourceService blocklistSourceService;

    @Transactional(readOnly = true)
    public PublicStatsResponse collect() {
        final var to = analyticsDays.today().minusDays(1);
        final var from = to.minusDays(DAYS - 1L);
        final var window = statsDays.window(from, to);
        final var now = statsDays.storedNow();
        final var daily = AnalyticsSeries.zeroFilled(trafficStatsRepository.findDaily(from, to), from, to);

        return new PublicStatsResponse(
                from,
                to,
                linksStatsRepository.countInventory(now).active(),
                linksStatsRepository.countCreated(window.start(), window.end()).total(),
                AnalyticsSeries.totalsOf(daily).clicks(),
                daily.stream().map(day -> new DailyClicks(day.date(), day.clicks())).toList(),
                new BlockedLinks(
                        linksStatsRepository.countBlockedByReason().stream().mapToLong(CountEntry::count).sum(),
                        hourlyCountRepository.count(HourlySeries.BLOCKS_BY_REASON, window.start(), window.end()).stream()
                                .mapToLong(HourlyCount::count)
                                .sum()),
                safetyStatsRepository.countResolvedVisitorReports(window.start(), window.end()),
                averageOf(safetyStatsRepository.findResolveMinutes(window.start(), window.end())),
                blocklists(),
                now);
    }

    private List<Blocklist> blocklists() {
        return blocklistSourceService.list().stream()
                .filter(BlocklistSourceResponse::enabled)
                .filter(source -> source.domainCount() != null && source.domainCount() > 0)
                .map(source -> new Blocklist(source.name(), source.action(), source.domainCount()))
                .sorted(Comparator.comparingLong(Blocklist::entries).reversed().thenComparing(Blocklist::name))
                .toList();
    }

    private static Long averageOf(final List<Long> minutes) {
        if (minutes.isEmpty()) {
            return null;
        }
        return Math.round(minutes.stream().mapToLong(Long::longValue).average().orElseThrow());
    }

}
