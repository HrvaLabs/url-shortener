package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.RedisKeyCounts;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisKeyCounter {

    private static final List<String> PREFIXES =
            List.of("blocklist:", "rate-limit:", "analytics:", "rdap:", "dbl:", "mcp:connect:", "urls::");
    private static final int SCAN_BATCH = 1_000;
    private static final int MAX_SCANNED_KEYS = 100_000;

    private final StringRedisTemplate redisTemplate;

    public RedisKeyCounts count() {
        final var counts = new LinkedHashMap<String, Long>();
        PREFIXES.forEach(prefix -> counts.put(prefix, 0L));
        var other = 0L;
        var scanned = 0L;
        var capped = false;

        try (final var cursor = redisTemplate.scan(ScanOptions.scanOptions().count(SCAN_BATCH).build())) {
            while (cursor.hasNext()) {
                if (scanned == MAX_SCANNED_KEYS) {
                    capped = true;
                    break;
                }
                final var key = cursor.next();
                scanned++;
                final var prefix = PREFIXES.stream().filter(key::startsWith).findFirst();
                if (prefix.isPresent()) {
                    counts.merge(prefix.get(), 1L, Long::sum);
                } else {
                    other++;
                }
            }
        }
        return new RedisKeyCounts(counts, other, scanned, capped);
    }

}
