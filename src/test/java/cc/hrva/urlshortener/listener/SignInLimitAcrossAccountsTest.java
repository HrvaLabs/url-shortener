package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignInLimitAcrossAccountsTest {

    private static final String NETWORK = "203.0.113.50";
    private static final String MINE = "mine@hrva.cc";
    private static final String VICTIM = "victim@hrva.cc";
    private static final int NETWORK_LIMIT = 20;

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final InMemoryRedis redis = new InMemoryRedis();

    @Test
    void signingIntoYourOwnAccountDoesNotResetTheNetworksLimitForGuessesAtAnother() {
        final var loginAttempts = loginAttempts(1_000);
        for (var guess = 1; guess <= 9; guess++) {
            wrongPassword(loginAttempts, VICTIM);
        }

        rightPassword(loginAttempts, MINE);

        for (var guess = 10; guess < NETWORK_LIMIT; guess++) {
            assertThatCode(() -> loginAttempts.checkSignIn(NETWORK, VICTIM)).doesNotThrowAnyException();
            wrongPassword(loginAttempts, VICTIM);
        }
        assertThatCode(() -> loginAttempts.checkSignIn(NETWORK, VICTIM)).doesNotThrowAnyException();
        wrongPassword(loginAttempts, VICTIM);

        assertThatThrownBy(() -> loginAttempts.checkSignIn(NETWORK, VICTIM))
                .isInstanceOfSatisfying(RateLimitExceededException.class, refused -> {
                    assertThat(refused).hasMessage("Too many sign-in attempts. Try again in 60 minutes.");
                    assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofHours(1));
                });
        assertThatThrownBy(() -> loginAttempts.checkSignIn(NETWORK, MINE)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void anotherAccountsSuccessDoesNotResetAnAccountsCounter() {
        final var loginAttempts = loginAttempts(10);
        for (var guess = 1; guess <= 9; guess++) {
            wrongPassword(loginAttempts, VICTIM);
        }

        rightPassword(loginAttempts, MINE);
        wrongPassword(loginAttempts, VICTIM);

        assertThatThrownBy(() -> loginAttempts.checkSignIn("198.51.100.9", VICTIM))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        refused -> assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
        assertThatCode(() -> loginAttempts.checkSignIn("198.51.100.9", MINE)).doesNotThrowAnyException();
    }

    private LoginAttemptService loginAttempts(final int accountAttempts) {
        final var redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        final var properties = new AppProperties();
        properties.setMaxLoginAttempts((long) NETWORK_LIMIT);
        properties.getLoginLimit().setAccountAttempts(accountAttempts);
        when(clientIpResolver.getClientIp(request)).thenReturn(NETWORK);

        return new DefaultLoginAttemptService(properties, new RedisFixedWindowRateLimiter(redisTemplate), redisTemplate);
    }

    private void wrongPassword(final LoginAttemptService loginAttempts, final String email) {
        new AuthenticationFailureListener(request, clientIpResolver, loginAttempts).onApplicationEvent(
                new AuthenticationFailureBadCredentialsEvent(UsernamePasswordAuthenticationToken.unauthenticated(email, "guess"),
                        new BadCredentialsException("Bad credentials")));
    }

    private static void rightPassword(final LoginAttemptService loginAttempts, final String email) {
        new AuthenticationSuccessEventListener(loginAttempts).onApplicationEvent(
                new AuthenticationSuccessEvent(UsernamePasswordAuthenticationToken.unauthenticated(email, "right")));
    }

}
