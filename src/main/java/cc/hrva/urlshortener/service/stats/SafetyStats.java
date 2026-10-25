package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.DomainRuleCounts;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.ResolveTimes;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.SourceHealth;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.HourlySeries;
import cc.hrva.urlshortener.repository.stats.SafetyStatsRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.util.ReviewReasons;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SafetyStats {

    private static final List<String> SOURCE_KEYS = List.of("SYSTEM", "VISITOR");
    private static final List<String> REASON_KEYS = Arrays.stream(BlockReason.values()).map(BlockReason::name).toList();
    private static final double MEDIAN = 0.5;
    private static final double P90 = 0.9;
    private static final Pattern WEB_ADDRESS = Pattern.compile("https?://\\S+?(?=;?(?:\\s|$))", Pattern.CASE_INSENSITIVE);
    private static final String REDACTED_ADDRESS = "[url]";

    private final SafetyStatsRepository safetyStatsRepository;
    private final HourlyCountRepository hourlyCountRepository;
    private final DailyBuckets dailyBuckets;
    private final LinkReviewRepository linkReviewRepository;
    private final DomainRuleRepository domainRuleRepository;
    private final ScammerSignalService scammerSignalService;
    private final BlocklistSourceService blocklistSourceService;

    public SafetyStatsResponse collect(final StatsWindow window) {
        final var reviewsOpened = hourlyCountRepository.count(HourlySeries.REVIEWS_BY_SOURCE, window.start(), window.end());
        final var blocked = hourlyCountRepository.count(HourlySeries.BLOCKS_BY_REASON, window.start(), window.end());
        final var resolveMinutes = safetyStatsRepository.findResolveMinutes(window.start(), window.end());
        final var sources = blocklistSourceService.list().stream()
                .map(SafetyStats::healthOf)
                .toList();

        return new SafetyStatsResponse(
                window.days(),
                window.from(),
                window.to(),
                dailyBuckets.stack(reviewsOpened, SOURCE_KEYS, window),
                linkReviewRepository.countByResolvedAtIsNull(),
                linkReviewRepository.countByResolvedAtIsNullAndReasonsContaining(ReviewReasons.VISITOR_REPORT),
                safetyStatsRepository.countResolvedByResolution(window.start(), window.end()),
                new ResolveTimes(resolveMinutes.size(), nearestRank(resolveMinutes, MEDIAN), nearestRank(resolveMinutes, P90)),
                dailyBuckets.stack(blocked, REASON_KEYS, window),
                new DomainRuleCounts(
                        domainRuleRepository.countByType(DomainRuleType.BLOCK),
                        domainRuleRepository.countByType(DomainRuleType.ALLOW)),
                scammerSignalService.possibleScammerIds().size(),
                sources);
    }

    static Long nearestRank(final List<Long> sorted, final double percentile) {
        if (sorted.isEmpty()) {
            return null;
        }
        return sorted.get((int) Math.ceil(percentile * sorted.size()) - 1);
    }

    private static SourceHealth healthOf(final BlocklistSourceResponse source) {
        return new SourceHealth(
                source.id(),
                source.name(),
                source.action(),
                source.enabled(),
                source.domainCount(),
                source.memoryBytes(),
                source.lastSuccessAt(),
                withoutWebAddresses(source.lastError()));
    }

    private static String withoutWebAddresses(final String text) {
        return text == null ? null : WEB_ADDRESS.matcher(text).replaceAll(REDACTED_ADDRESS);
    }

}
