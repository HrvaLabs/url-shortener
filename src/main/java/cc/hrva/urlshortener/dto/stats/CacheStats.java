package cc.hrva.urlshortener.dto.stats;

public record CacheStats(long hits, long misses, Double hitRatio) {

    public static CacheStats of(final long hits, final long misses) {
        final var lookups = hits + misses;
        return new CacheStats(hits, misses, lookups == 0 ? null : hits * 100.0 / lookups);
    }

}
