package cc.hrva.urlshortener.service.burst;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisBurstCounter {

    private static final String KEY_PREFIX = "burst:links:";
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final RedisScript<List<String>> RECORD_IN_WINDOW = (RedisScript) RedisScript.of("""
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZRANGE', KEYS[1], 0, -1)
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    public List<String> record(final String domain, final String member, final Instant now, final Duration window) {
        return recordUnderKey(KEY_PREFIX + domain, member, now, window);
    }

    public List<String> recordUnderKey(final String key, final String member, final Instant now, final Duration window) {
        final var members = redisTemplate.execute(RECORD_IN_WINDOW, List.of(key), Long.toString(now.toEpochMilli()), member,
                Long.toString(now.minus(window).toEpochMilli()), Long.toString(window.toMillis()));
        return members == null ? List.of() : members;
    }

}
