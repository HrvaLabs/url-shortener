package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.AdminStatsService;
import cc.hrva.urlshortener.service.WeeklyDigestService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.digest.DigestWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultWeeklyDigestService implements WeeklyDigestService {

    private static final int TOP_LINKS = 5;
    private static final Set<String> MANUAL_BLOCKS = Set.of(BlockReason.ADMIN.name(), BlockReason.USER_BLOCKED.name());

    private final AdminStatsService adminStatsService;
    private final AnalyticsDays analyticsDays;

    @Override
    public WeeklyDigest collect(final LocalDate today) {
        final var week = DigestWeek.lastClosedBefore(today);
        final var weekBefore = week.previous();
        final var traffic = adminStatsService.traffic(week.from(), week.to());
        final var links = adminStatsService.links(week.from(), week.to());
        final var users = adminStatsService.users(week.from(), week.to());
        final var safety = adminStatsService.safety(week.from(), week.to());
        final var trafficBefore = adminStatsService.traffic(weekBefore.from(), weekBefore.to());
        final var linksBefore = adminStatsService.links(weekBefore.from(), weekBefore.to());
        final var usersBefore = adminStatsService.users(weekBefore.from(), weekBefore.to());

        return new WeeklyDigest(
                week.from(),
                week.to(),
                new Change(traffic.totals().clicks(), trafficBefore.totals().clicks()),
                traffic.totals().uniqueVisitors(),
                traffic.totals().botClicks(),
                new Change(links.createdInRange().total(), linksBefore.createdInRange().total()),
                totalsPerKey(links.created(), key -> true),
                new Change(total(users.signups()), total(usersBefore.signups())),
                users.activeLast7Days(),
                total(safety.reviewsOpened()),
                safety.resolved().stream().mapToLong(CountEntry::count).sum(),
                totalsPerKey(safety.blocked(), key -> !MANUAL_BLOCKS.contains(key)),
                traffic.topLinks().stream()
                        .limit(TOP_LINKS)
                        .map(DefaultWeeklyDigestService::topLinkOf)
                        .toList());
    }

    private static List<CountEntry> totalsPerKey(final StackedSeries series, final Predicate<String> counted) {
        return series.keys().stream()
                .filter(counted)
                .map(key -> new CountEntry(key, totalOf(series, key)))
                .filter(entry -> entry.count() > 0)
                .sorted(Comparator.comparingLong(CountEntry::count).reversed())
                .toList();
    }

    private static long total(final StackedSeries series) {
        return series.daily().stream()
                .flatMap(day -> day.counts().values().stream())
                .mapToLong(Long::longValue)
                .sum();
    }

    private static long totalOf(final StackedSeries series, final String key) {
        return series.daily().stream().mapToLong(day -> day.counts().getOrDefault(key, 0L)).sum();
    }

    private static WeeklyDigest.TopLink topLinkOf(final TrafficStatsResponse.TopLink link) {
        return new WeeklyDigest.TopLink(link.shortUrl(), link.destinationHost(), link.clicks());
    }

}
