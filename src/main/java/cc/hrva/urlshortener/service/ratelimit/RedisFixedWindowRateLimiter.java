package cc.hrva.urlshortener.service.ratelimit;

import java.time.Duration;
import java.util.List;
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

    private static final String KEY_PREFIX = "rate-limit:";
    private static final RedisScript<Long> FIXED_WINDOW_INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public long increment(final String key, final Duration window) {
        try {
            final var count = redisTemplate.execute(FIXED_WINDOW_INCREMENT, List.of(KEY_PREFIX + key), Long.toString(window.toMillis()));
            return count != null ? count : Long.MAX_VALUE;
        } catch (final DataAccessException exception) {
            log.warn("Rate limit check failed, refusing the request exception={}", exception.getClass().getName());
            throw new RateLimiterUnavailableException(exception);
        }
    }

}
