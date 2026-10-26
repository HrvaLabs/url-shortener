package cc.hrva.urlshortener.service.ratelimit;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class RedisFixedWindowRateLimiterTest {

    private static final String KEY = "report:" + "a".repeat(64);
    private static final Duration WINDOW = Duration.ofHours(1);

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private RedisFixedWindowRateLimiter rateLimiter;

    @Test
    void countsTheKeyInItsWindow() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate-limit:" + KEY)), eq("3600000"))).thenReturn(3L);

        assertThat(rateLimiter.increment(KEY, WINDOW)).isEqualTo(3);
        assertThat(rateLimiter.tryAcquire(KEY, 3, WINDOW)).isTrue();
        assertThat(rateLimiter.tryAcquire(KEY, 2, WINDOW)).isFalse();
    }

    @Test
    void aMissingCountIsOverEveryLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThat(rateLimiter.increment(KEY, WINDOW)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aFailedCheckIsLoggedWithoutAnOutcomeAndLeftToTheCaller(final CapturedOutput output) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal/10.0.0.5:6379"));

        assertThatThrownBy(() -> rateLimiter.increment(KEY, WINDOW))
                .isInstanceOf(RateLimiterUnavailableException.class)
                .hasCauseInstanceOf(RedisConnectionFailureException.class);

        assertThat(output.getAll().lines().filter(line -> line.contains("Rate limit check failed")))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN", "exception=" + RedisConnectionFailureException.class.getName()));
        assertThat(output.getAll()).doesNotContain("refusing", "allowing", "redis.internal", "10.0.0.5", KEY);
    }

}
