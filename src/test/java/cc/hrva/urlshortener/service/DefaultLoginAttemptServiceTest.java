package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultLoginAttemptServiceTest {

    private static final String ANA = "ana@hrva.cc";
    private static final String HOME = "203.0.113.7";
    private static final String TOO_MANY_IN_AN_HOUR = "Too many sign-in attempts. Try again in 60 minutes.";

    private final InMemoryRedis redis = new InMemoryRedis();
    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        loginAttemptService = InMemoryLoginAttempts.over(redis, 3);
    }

    @Test
    void aNetworkIsRefusedWithA429AfterTheLimitForTheRestOfTheHour() {
        loginAttemptService.signInFailed(HOME, "a@hrva.cc");
        loginAttemptService.signInFailed(HOME, "b@hrva.cc");
        assertThatCode(() -> loginAttemptService.checkSignIn(HOME, "c@hrva.cc")).doesNotThrowAnyException();

        loginAttemptService.signInFailed(HOME, "c@hrva.cc");

        assertThatThrownBy(() -> loginAttemptService.checkSignIn(HOME, "d@hrva.cc"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, refused -> {
                    assertThat(refused).hasMessage(TOO_MANY_IN_AN_HOUR);
                    assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofHours(1));
                });
        assertThatThrownBy(() -> loginAttemptService.checkNetwork(HOME)).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> loginAttemptService.checkSignIn("198.51.100.2", "d@hrva.cc")).doesNotThrowAnyException();
    }

    @Test
    void anAccountIsRefusedAfterTenAttemptsInFifteenMinutesFromAnyNetwork() {
        final var limited = InMemoryLoginAttempts.over(redis, 1_000);
        for (var network = 1; network <= 10; network++) {
            limited.signInFailed("198.51.100." + network, network % 2 == 0 ? ANA : " Ana@HRVA.cc ");
        }

        assertThatThrownBy(() -> limited.checkSignIn("192.0.2.1", ANA))
                .isInstanceOfSatisfying(RateLimitExceededException.class, refused -> {
                    assertThat(refused).hasMessage("Too many sign-in attempts. Try again in 15 minutes.");
                    assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofMinutes(15));
                });
        assertThatCode(() -> limited.checkSignIn("192.0.2.1", "bob@hrva.cc")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessClearsOnlyTheAccountThatSignedInAndNeverTheNetwork() {
        final var limited = InMemoryLoginAttempts.over(redis, 3);
        limited.signInFailed(HOME, "mine@hrva.cc");
        limited.signInFailed(HOME, ANA);
        limited.signInFailed(HOME, ANA);

        limited.signInSucceeded("mine@hrva.cc");

        assertThatThrownBy(() -> limited.checkSignIn(HOME, "mine@hrva.cc")).isInstanceOf(RateLimitExceededException.class);
        assertThat(limited.attempts("account:", PageRequest.of(0, 20)).getContent())
                .singleElement().satisfies(row -> assertThat(row.attempts()).isEqualTo(2));
    }

    @Test
    void secondStepFailuresCountForTheNetworkOnly() {
        loginAttemptService.networkFailed(HOME);
        loginAttemptService.networkFailed(HOME);
        loginAttemptService.networkFailed(HOME);

        assertThatThrownBy(() -> loginAttemptService.checkNetwork(HOME)).isInstanceOf(RateLimitExceededException.class);
        assertThat(loginAttemptService.attempts("account:", PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void ipv6AddressesCountPerSlash64() {
        for (var host = 1; host <= 3; host++) {
            loginAttemptService.networkFailed("2001:db8:1:2::" + host);
        }

        assertThatThrownBy(() -> loginAttemptService.checkNetwork("2001:db8:1:2:ffff::1")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> loginAttemptService.checkNetwork("2001:db8:1:3::1")).doesNotThrowAnyException();
    }

    @Test
    void theCountersHoldNoPlainAddressOrEmail() {
        loginAttemptService.signInFailed(HOME, ANA);

        assertThat(redis.keys()).hasSize(2).allSatisfy(key -> assertThat(key)
                .startsWith("rate-limit:login:")
                .doesNotContain(HOME)
                .doesNotContain("ana"));
        assertThat(redis.keys()).anySatisfy(key -> assertThat(key)
                .isEqualTo("rate-limit:" + RateLimiter.clientKey("login:network", HOME)));
    }

    @Test
    void theMinutesLeftAreRoundedUpAndOneIsSingular() {
        final var redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        final var properties = new AppProperties();
        properties.setMaxLoginAttempts(1L);
        final var service = new DefaultLoginAttemptService(properties, new RedisFixedWindowRateLimiter(redisTemplate), redisTemplate);
        service.networkFailed(HOME);

        when(redisTemplate.getExpire(any(String.class), any())).thenReturn(30_000L);
        assertThatThrownBy(() -> service.checkNetwork(HOME))
                .isInstanceOfSatisfying(RateLimitExceededException.class, refused -> {
                    assertThat(refused).hasMessage("Too many sign-in attempts. Try again in 1 minute.");
                    assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
                });

        when(redisTemplate.getExpire(any(String.class), any())).thenReturn(61_000L);
        assertThatThrownBy(() -> service.checkNetwork(HOME)).hasMessage("Too many sign-in attempts. Try again in 2 minutes.");
    }

    @Test
    void twoStepAndPasswordConfirmationLocksStayPerAccountForADay() {
        failed("2fa:42", 3);

        assertThat(loginAttemptService.isBlocked("2fa:42")).isTrue();
        assertThat(loginAttemptService.isBlocked("2fa:43")).isFalse();
        assertThat(redis.timeToLiveOfKeyContaining("2fa:42")).isEqualTo(Duration.ofDays(1));

        loginAttemptService.loginSucceeded("2fa:42");
        assertThat(loginAttemptService.isBlocked("2fa:42")).isFalse();
    }

    @Test
    void redisDownLetsEveryAttemptThrough() {
        final var redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("down"));
        when(redisTemplate.execute(any(), any(), any(Object[].class))).thenThrow(new QueryTimeoutException("down"));
        when(redisTemplate.scan(any())).thenThrow(new QueryTimeoutException("down"));
        final var properties = new AppProperties();
        properties.setMaxLoginAttempts(1L);
        final var service = new DefaultLoginAttemptService(properties, new RedisFixedWindowRateLimiter(redisTemplate), redisTemplate);

        assertThatCode(() -> {
            service.signInFailed(HOME, ANA);
            service.checkNetwork(HOME);
            service.loginFailed("2fa:1");
        }).doesNotThrowAnyException();
        assertThat(service.isBlocked("2fa:1")).isFalse();
        assertThat(service.attempts(null, PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void redisDownRefusesAPasswordSignInWithARetryTime() {
        final var redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("down"));
        final var properties = new AppProperties();
        properties.setMaxLoginAttempts(20L);
        final var service = new DefaultLoginAttemptService(properties, new RedisFixedWindowRateLimiter(redisTemplate), redisTemplate);

        assertThatThrownBy(() -> service.checkSignIn(HOME, ANA))
                .isInstanceOfSatisfying(SignInUnavailableException.class, refused -> {
                    assertThat(refused).hasMessage("Signing in with a password isn't available right now. Try again in a minute.");
                    assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void attemptsComeMostFirstWithTheKeyBreakingTies() {
        failed("2fa:2", 2);
        failed("2fa:1", 2);
        failed("reauth:7", 4);
        failed("2fa:3", 1);

        assertThat(loginAttemptService.attempts(null, PageRequest.of(0, 20)).getContent()).containsExactly(
                new LoginAttemptResponse("reauth:7", 4),
                new LoginAttemptResponse("2fa:1", 2),
                new LoginAttemptResponse("2fa:2", 2),
                new LoginAttemptResponse("2fa:3", 1));
    }

    @Test
    void attemptsCanBeSortedFewestFirstOrByKey() {
        failed("2fa:2", 3);
        failed("2fa:1", 1);
        failed("reauth:7", 2);

        assertThat(loginAttemptService.attempts(null, PageRequest.of(0, 20, Sort.by(Sort.Order.asc("attempts")))).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("2fa:1", "reauth:7", "2fa:2");
        assertThat(loginAttemptService.attempts(null, PageRequest.of(0, 20, Sort.by(Sort.Order.desc("key")))).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("reauth:7", "2fa:2", "2fa:1");
    }

    @Test
    void aPageOfAttemptsHoldsItsShareAndTheTotal() {
        for (var account = 1; account <= 5; account++) {
            failed("2fa:" + account, account);
        }

        final var page = loginAttemptService.attempts(null, PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(LoginAttemptResponse::key).containsExactly("2fa:3", "2fa:2");
    }

    @Test
    void theSearchFindsANetworkByItsAddressAndAnAccountByItsEmail() {
        loginAttemptService.signInFailed(HOME, ANA);
        loginAttemptService.signInFailed("198.51.100.9", "bob@hrva.cc");
        failed("2fa:42", 1);

        assertThat(loginAttemptService.attempts(" " + HOME + " ", PageRequest.of(0, 20)).getContent())
                .extracting(LoginAttemptResponse::key).singleElement().asString().startsWith("network:");
        assertThat(loginAttemptService.attempts("ANA@hrva.cc", PageRequest.of(0, 20)).getContent())
                .extracting(LoginAttemptResponse::key).singleElement().asString().startsWith("account:");
        assertThat(loginAttemptService.attempts("2FA:", PageRequest.of(0, 20)).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("2fa:42");
        assertThat(loginAttemptService.attempts("network:", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void clearingRemovesEveryLoginCounterAndNothingElse() {
        loginAttemptService.signInFailed(HOME, ANA);
        failed("reauth:7", 1);
        final var redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        redisTemplate.opsForValue().set("rate-limit:create:user:7", "3", Duration.ofHours(1));

        loginAttemptService.clearLoginAttempts();

        assertThat(loginAttemptService.attempts(null, PageRequest.of(0, 20))).isEmpty();
        assertThat(redis.keys()).containsExactly("rate-limit:create:user:7");
    }

    @Test
    void attemptsRefuseToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> loginAttemptService.attempts(null, PageRequest.of(0, 20, Sort.by("blocked"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be attempts or key");
    }

    private void failed(final String key, final int times) {
        for (var attempt = 0; attempt < times; attempt++) {
            loginAttemptService.loginFailed(key);
        }
    }

}
