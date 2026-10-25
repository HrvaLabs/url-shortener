package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.service.AdminStatsService;
import cc.hrva.urlshortener.service.stats.StatsDays;
import cc.hrva.urlshortener.service.stats.TrafficStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultAdminStatsService implements AdminStatsService {

    private final StatsDays statsDays;
    private final TrafficStats trafficStats;

    @Override
    public TrafficStatsResponse traffic(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        return trafficStats.collect(statsDays.window(range));
    }

}
