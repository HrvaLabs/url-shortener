package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.service.AdminStatsService;
import cc.hrva.urlshortener.service.stats.LinksStats;
import cc.hrva.urlshortener.service.stats.SafetyStats;
import cc.hrva.urlshortener.service.stats.StatsDays;
import cc.hrva.urlshortener.service.stats.SystemStats;
import cc.hrva.urlshortener.service.stats.TrafficStats;
import cc.hrva.urlshortener.service.stats.UsersStats;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultAdminStatsService implements AdminStatsService {

    private final StatsDays statsDays;
    private final TrafficStats trafficStats;
    private final LinksStats linksStats;
    private final UsersStats usersStats;
    private final SafetyStats safetyStats;
    private final SystemStats systemStats;

    @Override
    public TrafficStatsResponse traffic(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        return trafficStats.collect(statsDays.window(range));
    }

    @Override
    public LinksStatsResponse links(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        return linksStats.collect(statsDays.window(range));
    }

    @Override
    public UsersStatsResponse users(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        return usersStats.collect(statsDays.window(range));
    }

    @Override
    public SafetyStatsResponse safety(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        return safetyStats.collect(statsDays.window(range));
    }

    @Override
    public SystemStatsResponse system(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        return systemStats.collect(statsDays.window(range));
    }

    @Override
    public TrafficStatsResponse traffic(final LocalDate from, final LocalDate to) {
        return trafficStats.collect(statsDays.window(from, to));
    }

    @Override
    public LinksStatsResponse links(final LocalDate from, final LocalDate to) {
        return linksStats.collect(statsDays.window(from, to));
    }

    @Override
    public UsersStatsResponse users(final LocalDate from, final LocalDate to) {
        return usersStats.collect(statsDays.window(from, to));
    }

    @Override
    public SafetyStatsResponse safety(final LocalDate from, final LocalDate to) {
        return safetyStats.collect(statsDays.window(from, to));
    }

}
