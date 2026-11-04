package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.notification.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BrowserTimeUsedTest {

    private final InMemoryRedis inMemoryRedis = new InMemoryRedis();
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T23:59:00Z"));
    private final BrowserTimeUsed browserTimeUsed = new BrowserTimeUsed(redisTemplate, clock);

    @BeforeEach
    void setUp() {
        inMemoryRedis.backing(redisTemplate);
    }

    @Test
    void addsUpTheMillisecondsOfOneUtcDayKeptForTwoDays() {
        browserTimeUsed.add(4210L);
        browserTimeUsed.add(790L);

        assertThat(inMemoryRedis.keys()).containsExactly("browser-run:ms:2026-11-03");
        assertThat(inMemoryRedis.timeToLiveOfKeyContaining("browser-run:ms:")).isEqualTo(Duration.ofDays(2));
        assertThat(browserTimeUsed.secondsToday()).hasValue(5.0);
    }

    @Test
    void aNewUtcDayStartsAtZero() {
        browserTimeUsed.add(4210L);

        clock.advance(Duration.ofMinutes(2));

        assertThat(browserTimeUsed.secondsToday()).hasValue(0.0);
        browserTimeUsed.add(1500L);
        assertThat(browserTimeUsed.secondsToday()).hasValue(1.5);
        assertThat(inMemoryRedis.keys()).containsExactlyInAnyOrder("browser-run:ms:2026-11-03", "browser-run:ms:2026-11-04");
    }

    @Test
    void nothingOrZeroAddsNothing() {
        final var untouched = mock(StringRedisTemplate.class);
        final var counter = new BrowserTimeUsed(untouched, clock);

        counter.add(null);
        counter.add(0L);

        verifyNoInteractions(untouched);
    }

    @Test
    void aRedisFailureIsSoft() {
        doThrow(new RedisConnectionFailureException("down")).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
        doThrow(new RedisConnectionFailureException("down")).when(redisTemplate).opsForValue();

        assertThatCode(() -> browserTimeUsed.add(100L)).doesNotThrowAnyException();
        assertThat(browserTimeUsed.secondsToday()).isEmpty();
    }

    @Test
    void anUnreadableValueIsUnknown() {
        redisTemplate.opsForValue().set("browser-run:ms:2026-11-03", "lots", Duration.ofDays(2));

        assertThat(browserTimeUsed.secondsToday()).isEmpty();
    }

}
