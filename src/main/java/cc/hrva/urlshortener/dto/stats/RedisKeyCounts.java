package cc.hrva.urlshortener.dto.stats;

import java.util.Map;

public record RedisKeyCounts(Map<String, Long> counts, long other, long scanned, boolean capped) {
}
