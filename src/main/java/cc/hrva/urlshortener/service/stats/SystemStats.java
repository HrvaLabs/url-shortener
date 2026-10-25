package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.RedisKeyCounts;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse.ComponentHealth;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse.DatabaseStats;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse.RedisStats;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.repository.ShortUrlUniqueIndex;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.HourlySeries;
import cc.hrva.urlshortener.repository.stats.SystemStatsRepository;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore.RedisMemory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SystemStats {

    private static final List<String> STATUS_KEYS =
            List.of(EmailLog.STATUS_SENT, EmailLog.STATUS_FAILED, EmailLog.STATUS_SENDING, EmailLog.STATUS_RESENT);
    private static final String DATABASE = "db";
    private static final String REDIS = "redis";
    private static final String JAVA_VERSION = "java.version";

    private final RuntimeMetrics runtimeMetrics;
    private final HealthStatuses healthStatuses;
    private final AppVersion appVersion;
    private final BlocklistStore blocklistStore;
    private final RedisKeyCounter redisKeyCounter;
    private final SystemStatsRepository systemStatsRepository;
    private final ShortUrlUniqueIndex shortUrlUniqueIndex;
    private final HourlyCountRepository hourlyCountRepository;
    private final DailyBuckets dailyBuckets;
    private final Environment environment;

    public SystemStatsResponse collect(final StatsWindow window) {
        final var heap = runtimeMetrics.heap();
        final var database = systemStatsRepository.databaseInfo();
        final var emails = hourlyCountRepository.count(HourlySeries.EMAILS_BY_STATUS, window.start(), window.end());

        return new SystemStatsResponse(
                window.days(),
                window.from(),
                window.to(),
                appVersion.current(),
                LocalDateTime.ofInstant(runtimeMetrics.startedAt(), ZoneId.systemDefault()),
                RuntimeMetrics.formatUptime(runtimeMetrics.uptime()),
                System.getProperty(JAVA_VERSION),
                List.of(environment.getActiveProfiles()),
                heap.usedBytes(),
                heap.maxBytes(),
                runtimeMetrics.redirectTimings(),
                runtimeMetrics.requestCounts(),
                runtimeMetrics.urlCacheStats(),
                new ComponentHealth(healthStatuses.status(DATABASE), healthStatuses.status(REDIS)),
                redisStats(),
                new DatabaseStats(database.product(), database.version(), database.sizeBytes(), shortUrlUniqueIndex.enforced()),
                dailyBuckets.stack(emails, STATUS_KEYS, window));
    }

    private RedisStats redisStats() {
        final var memory = redisMemory();
        final var keys = redisKeys();
        if (memory == null) {
            return new RedisStats(null, null, keys);
        }
        return new RedisStats(memory.usedMemory(), memory.maxMemory(), keys);
    }

    private RedisMemory redisMemory() {
        try {
            return blocklistStore.memory();
        } catch (final RuntimeException exception) {
            log.warn("Could not read the Redis memory for the system stats exception={}", exception.getClass().getName());
            return null;
        }
    }

    private RedisKeyCounts redisKeys() {
        try {
            return redisKeyCounter.count();
        } catch (final RuntimeException exception) {
            log.warn("Could not count the Redis keys for the system stats exception={}", exception.getClass().getName());
            return null;
        }
    }

}
