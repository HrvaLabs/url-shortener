package cc.hrva.urlshortener.service.ratelimit;

import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountEmailLimitsTest {

    private static final String HOME = "203.0.113.7";

    private final InMemoryRedis redis = new InMemoryRedis();
    private AccountEmailLimits limits;

    @BeforeEach
    void setUp() {
        final var redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        limits = new AccountEmailLimits(new RedisFixedWindowRateLimiter(redisTemplate));
    }

    @Test
    void aNetworkMayAskTenTimesAnHourAndIsThenToldWhenToTryAgain() {
        IntStream.range(0, 10).forEach(request -> assertThatCode(() -> limits.checkNetwork(HOME)).doesNotThrowAnyException());

        assertThatThrownBy(() -> limits.checkNetwork(HOME))
                .isInstanceOfSatisfying(RateLimitExceededException.class, refused -> {
                    assertThat(refused).hasMessage("Too many emails asked for from this network. Try again in 60 minutes.");
                    assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofHours(1));
                });
        assertThatCode(() -> limits.checkNetwork("198.51.100.2")).doesNotThrowAnyException();
    }

    @Test
    void anAddressGetsThreeAccountEmailsAnHourWhateverTheCaseOrSpaces() {
        assertThat(limits.allowsEmailTo("ana@hrva.cc")).isTrue();
        assertThat(limits.allowsEmailTo(" Ana@Hrva.cc")).isTrue();
        assertThat(limits.allowsEmailTo("ANA@HRVA.CC ")).isTrue();
        assertThat(limits.allowsEmailTo("ana@hrva.cc")).isFalse();
        assertThat(limits.allowsEmailTo("ben@hrva.cc")).isTrue();
    }

    @Test
    void theCountersHoldHashesNeverTheAddressOrTheNetwork() {
        limits.checkNetwork(HOME);
        limits.allowsEmailTo("ana@hrva.cc");

        assertThat(redis.keys()).hasSize(2).allSatisfy(key -> assertThat(key)
                .startsWith("rate-limit:account-email:")
                .doesNotContain(HOME)
                .doesNotContain("ana"));
    }

    @Test
    void redisDownLetsTheRequestAndTheEmailThrough() {
        final var redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("down"));
        final var unavailable = new AccountEmailLimits(new RedisFixedWindowRateLimiter(redisTemplate));

        assertThatCode(() -> unavailable.checkNetwork(HOME)).doesNotThrowAnyException();
        assertThat(unavailable.allowsEmailTo("ana@hrva.cc")).isTrue();
    }

}
