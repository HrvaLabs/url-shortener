package cc.hrva.urlshortener.dto.stats;

public record RedirectTiming(String source, long count, Double meanMs, Double maxMs) {
}
