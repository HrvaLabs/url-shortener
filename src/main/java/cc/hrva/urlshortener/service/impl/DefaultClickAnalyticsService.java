package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.model.enums.StatDimension;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.CountryCodes;
import cc.hrva.urlshortener.service.analytics.ReferrerClassifier;
import cc.hrva.urlshortener.service.analytics.UniqueVisitorCounter;
import cc.hrva.urlshortener.service.analytics.UserAgentClassifier;
import cc.hrva.urlshortener.service.analytics.UserAgentDetails;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultClickAnalyticsService implements ClickAnalyticsService {

    private static final int MAX_VALUES_PER_DIMENSION = 50;
    private static final int MAX_VALUE_LENGTH = 255;
    private static final String OTHER = "other";
    private static final String URLS_CACHE = "urls";
    private static final String STEP_CLICK = "click";
    private static final String STEP_BOT = "bot";
    private static final String STEP_VISIT = "visit";
    private static final String STEP_STATS = "stats";
    private static final String STEP_BREAKDOWN = "breakdown";

    private final TaskExecutor applicationTaskExecutor;
    private final LinkStatsRepository linkStatsRepository;
    private final UniqueVisitorCounter uniqueVisitorCounter;
    private final ReferrerClassifier referrerClassifier;
    private final AnalyticsDays analyticsDays;
    private final UrlRepository urlRepository;
    private final TransactionOperations transactionOperations;
    private final CacheManager cacheManager;

    @Override
    public void recordClick(final Long urlId, final String shortUrl, final ClickRequest click) {
        if (urlId == null) {
            return;
        }

        try {
            final var day = analyticsDays.today();
            applicationTaskExecutor.execute(() -> step(urlId, STEP_CLICK, () -> record(urlId, shortUrl, day, click)));
        } catch (final RuntimeException exception) {
            log.warn("Click analytics dropped urlId={} exception={}", urlId, exception.getClass().getName());
        }
    }

    @Override
    public boolean isBot(final ClickRequest click) {
        try {
            return UserAgentClassifier.isBot(click.userAgent());
        } catch (final RuntimeException exception) {
            return false;
        }
    }

    private void record(final long urlId, final String shortUrl, final LocalDate day, final ClickRequest click) {
        final var userAgent = UserAgentClassifier.classify(click.userAgent());
        if (userAgent.bot()) {
            step(urlId, STEP_BOT, () -> linkStatsRepository.incrementDailyStats(urlId, day, 0, 0, 1));
            return;
        }

        final var newVisitor = uniqueVisitorCounter.isNewVisitor(urlId, day, click);
        if (newVisitor) {
            step(urlId, STEP_VISIT, () -> recordVisit(urlId, shortUrl));
        }
        step(urlId, STEP_STATS, () -> linkStatsRepository.incrementDailyStats(urlId, day, 1, newVisitor ? 1 : 0, 0));
        step(urlId, STEP_BREAKDOWN, () -> incrementBreakdown(urlId, day, click, userAgent));
    }

    private void recordVisit(final long urlId, final String shortUrl) {
        final var switchedOff = transactionOperations.execute(status -> {
            urlRepository.recordVisit(urlId, LocalDateTime.now());
            return urlRepository.deactivateAtVisitLimit(urlId) > 0;
        });
        if (Boolean.TRUE.equals(switchedOff)) {
            evictFromCache(urlId, shortUrl);
        }
    }

    private void evictFromCache(final long urlId, final String shortUrl) {
        try {
            final var cache = cacheManager.getCache(URLS_CACHE);
            if (cache != null) {
                cache.evict(shortUrl);
            }
        } catch (final RuntimeException exception) {
            log.warn("URL cache eviction failed urlId={} exception={}", urlId, exception.getClass().getName());
        }
    }

    private void incrementBreakdown(final long urlId, final LocalDate day, final ClickRequest click, final UserAgentDetails userAgent) {
        final var values = new EnumMap<StatDimension, String>(StatDimension.class);
        values.put(StatDimension.REFERRER, referrerClassifier.classify(click));
        values.put(StatDimension.COUNTRY, CountryCodes.normalize(click.country()));
        values.put(StatDimension.DEVICE, userAgent.device());
        values.put(StatDimension.BROWSER, userAgent.browser());
        values.put(StatDimension.OS, userAgent.os());

        final var stored = linkStatsRepository.findBreakdownValues(urlId, day);
        values.replaceAll((dimension, value) -> capped(bounded(value), stored.getOrDefault(dimension, Set.of())));
        linkStatsRepository.incrementBreakdown(urlId, day, values);
    }

    private static String bounded(final String value) {
        return value == null || value.length() > MAX_VALUE_LENGTH ? OTHER : value;
    }

    private static String capped(final String value, final Set<String> stored) {
        if (OTHER.equals(value) || stored.contains(value)) {
            return value;
        }

        final var named = stored.stream().filter(storedValue -> !OTHER.equals(storedValue)).count();
        return named < MAX_VALUES_PER_DIMENSION ? value : OTHER;
    }

    private static void step(final long urlId, final String step, final Runnable write) {
        try {
            write.run();
        } catch (final RuntimeException exception) {
            log.warn("Click analytics write failed urlId={} step={} exception={}", urlId, step, exception.getClass().getName());
        }
    }

}
