package cc.hrva.urlshortener.service.ratelimit;

import java.time.Duration;

public record RateLimitWindow(long count, Duration resetsIn) {
}
