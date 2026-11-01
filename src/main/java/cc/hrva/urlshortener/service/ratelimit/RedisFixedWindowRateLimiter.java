package cc.hrva.urlshortener.service.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisFixedWindowRateLimiter implements RateLimiter {

    public static final String KEY_PREFIX = "rate-limit:";
    private static final long NO_EXPIRY = -1;
    private static final RedisScript<Long> FIXED_WINDOW_INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);
    private static final RedisScript<List> FIXED_WINDOW_INCREMENT_WITH_RESET = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public long increment(final String key, final Duration window) {
        try {
            final var count = redisTemplate.execute(FIXED_WINDOW_INCREMENT, List.of(KEY_PREFIX + key), Long.toString(window.toMillis()));
            return count != null ? count : Long.MAX_VALUE;
        } catch (final DataAccessException exception) {
            log.warn("Rate limit check failed exception={}", exception.getClass().getName());
            throw new RateLimiterUnavailableException(exception);
        }
    }

    @Override
    public RateLimitWindow incrementWindow(final String key, final Duration window) {
        try {
            final List<?> result = redisTemplate.execute(FIXED_WINDOW_INCREMENT_WITH_RESET, List.of(KEY_PREFIX + key), Long.toString(window.toMillis()));
            if (result == null || result.size() < 2) {
                return new RateLimitWindow(Long.MAX_VALUE, window);
            }
            return new RateLimitWindow(((Number) result.get(0)).longValue(), Duration.ofMillis(((Number) result.get(1)).longValue()));
        } catch (final DataAccessException exception) {
            log.warn("Rate limit check failed exception={}", exception.getClass().getName());
            throw new RateLimiterUnavailableException(exception);
        }
    }

    @Override
    public Optional<Duration> resetsIn(final String key) {
        try {
            final var millisLeft = redisTemplate.getExpire(KEY_PREFIX + key, TimeUnit.MILLISECONDS);
            if (millisLeft == null || millisLeft == NO_EXPIRY) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofMillis(Math.max(0, millisLeft)));
        } catch (final DataAccessException exception) {
            log.warn("Rate limit reset lookup failed exception={}", exception.getClass().getName());
            return Optional.empty();
        }
    }

}
