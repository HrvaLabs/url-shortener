package cc.hrva.urlshortener.service.burst;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisBurstCounterTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final String NOW_MILLIS = Long.toString(NOW.toEpochMilli());
    private static final String CUTOFF_MILLIS = Long.toString(NOW.toEpochMilli() - 3_600_000);
    private static final String KEY = "burst:links:evil-login.com";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void recordPassesTheKeyMemberCutoffAndWindow() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq(NOW_MILLIS), eq("41|user:7"), eq(CUTOFF_MILLIS), eq("3600000")))
                .thenReturn(List.of("40|user:8", "41|user:7"));

        final var members = new RedisBurstCounter(redisTemplate).record("evil-login.com", "41|user:7", NOW, Duration.ofHours(1));

        assertThat(members).containsExactly("40|user:8", "41|user:7");
    }

    @Test
    void theWindowSetsTheCutoffAndTheExpiry() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq(NOW_MILLIS), eq("41|user:7"),
                eq(Long.toString(NOW.toEpochMilli() - 1_800_000)), eq("1800000")))
                .thenReturn(List.of("41|user:7"));

        assertThat(new RedisBurstCounter(redisTemplate).record("evil-login.com", "41|user:7", NOW, Duration.ofMinutes(30))).containsExactly("41|user:7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneScriptAddsTheLinkDropsWhatIsOlderThanTheWindowRefreshesTheExpiryAndListsTheMembers() {
        final ArgumentCaptor<RedisScript<List<String>>> script = ArgumentCaptor.forClass(RedisScript.class);
        when(redisTemplate.execute(script.capture(), anyList(), any(Object[].class))).thenReturn(List.of());

        new RedisBurstCounter(redisTemplate).record("evil-login.com", "41|user:7", NOW, Duration.ofHours(1));

        final var source = script.getValue().getScriptAsString();
        assertThat(source).containsSubsequence(
                "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])",
                "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[3])",
                "redis.call('PEXPIRE', KEYS[1], ARGV[4])",
                "return redis.call('ZRANGE', KEYS[1], 0, -1)");
        assertThat(ReturnType.fromJavaType(script.getValue().getResultType())).isEqualTo(ReturnType.MULTI);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), any(Object[].class));
    }

    @Test
    void noAnswerIsNoMembers() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThat(new RedisBurstCounter(redisTemplate).record("evil-login.com", "41|user:7", NOW, Duration.ofHours(1))).isEmpty();
    }

    @Test
    void redisFailuresReachTheCaller() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> new RedisBurstCounter(redisTemplate).record("evil-login.com", "41|user:7", NOW, Duration.ofHours(1)))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

}
