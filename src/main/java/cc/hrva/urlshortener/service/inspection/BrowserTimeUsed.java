package cc.hrva.urlshortener.service.inspection;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserTimeUsed {

    static final Duration KEPT_FOR = Duration.ofDays(2);
    private static final String KEY_PREFIX = "browser-run:ms:";
    private static final double MILLISECONDS_PER_SECOND = 1000.0;
    private static final RedisScript<Long> ADD_WITH_EXPIRY = RedisScript.of("""
            local total = redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return total
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public void add(final Long milliseconds) {
        if (milliseconds == null || milliseconds <= 0) {
            return;
        }
        try {
            redisTemplate.execute(ADD_WITH_EXPIRY, List.of(keyOfToday()), Long.toString(milliseconds), Long.toString(KEPT_FOR.toMillis()));
        } catch (final RuntimeException exception) {
            log.warn("Could not add the browser time used milliseconds={} exception={}", milliseconds, exception.getClass().getName());
        }
    }

    public OptionalDouble secondsToday() {
        try {
            final var value = redisTemplate.opsForValue().get(keyOfToday());
            return OptionalDouble.of(value == null ? 0 : Long.parseLong(value) / MILLISECONDS_PER_SECOND);
        } catch (final RuntimeException exception) {
            log.warn("Could not read today's browser time exception={}", exception.getClass().getName());
            return OptionalDouble.empty();
        }
    }

    String keyOfToday() {
        return KEY_PREFIX + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

}
