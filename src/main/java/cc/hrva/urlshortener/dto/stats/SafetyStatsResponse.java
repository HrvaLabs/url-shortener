package cc.hrva.urlshortener.dto.stats;

import cc.hrva.urlshortener.model.enums.BlocklistAction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SafetyStatsResponse(
        int days,
        LocalDate from,
        LocalDate to,
        StackedSeries reviewsOpened,
        long openReviews,
        long openVisitorReports,
        List<CountEntry> resolved,
        ResolveTimes resolveTimes,
        StackedSeries blocked,
        DomainRuleCounts domainRules,
        long possibleScammers,
        List<SourceHealth> sources) {

    public record ResolveTimes(long count, Long medianMinutes, Long p90Minutes) {}

    public record DomainRuleCounts(long block, long allow) {}

    public record SourceHealth(
            Long id,
            String name,
            BlocklistAction action,
            boolean enabled,
            Long domainCount,
            Long memoryBytes,
            LocalDateTime lastSuccessAt,
            String lastError) {}

}
