package cc.hrva.urlshortener.dto.stats;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SystemStatsResponse(
        int days,
        LocalDate from,
        LocalDate to,
        String appVersion,
        LocalDateTime startedAt,
        String uptime,
        String javaVersion,
        List<String> activeProfiles,
        long jvmHeapUsedBytes,
        long jvmHeapMaxBytes,
        List<RedirectTiming> redirects,
        RequestCounts requests,
        CacheStats cache,
        ComponentHealth health,
        RedisStats redis,
        DatabaseStats database,
        StackedSeries emails) {

    public record ComponentHealth(String database, String redis) {}

    public record RedisStats(Long usedMemoryBytes, Long maxMemoryBytes, RedisKeyCounts keys) {}

    public record DatabaseStats(String product, String version, Long sizeBytes, boolean shortUrlUnique) {}

}
