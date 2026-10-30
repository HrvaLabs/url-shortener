package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisPendingLoginStoreTest {

    private static final String KEY_PREFIX = "auth:pending-login:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InMemoryRedis redis = new InMemoryRedis();

    private StringRedisTemplate redisTemplate;
    private RedisPendingLoginStore pendingLoginStore;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        pendingLoginStore = new RedisPendingLoginStore(redisTemplate, JSON);
    }

    @Test
    void createsA43CharacterTokenThatExpiresInFiveMinutes() {
        final var token = pendingLoginStore.create(42L, true, SignInMethod.PASSWORD);

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(redis.keys()).containsExactly(KEY_PREFIX + token);
        assertThat(redis.timeToLiveOfKeyContaining(KEY_PREFIX)).isEqualTo(Duration.ofMinutes(5));
        assertThat(JSON.readTree(redisTemplate.opsForValue().get(KEY_PREFIX + token)))
                .isEqualTo(JSON.readTree("{\"userId\":42,\"rememberMe\":true,\"method\":\"PASSWORD\"}"));
    }

    @Test
    void findReturnsTheUserAndRememberMe() {
        final var token = pendingLoginStore.create(42L, true, SignInMethod.PASSWORD);

        assertThat(pendingLoginStore.find(token)).contains(new PendingLogin(42L, true, SignInMethod.PASSWORD));
        assertThat(pendingLoginStore.find(token)).contains(new PendingLogin(42L, true, SignInMethod.PASSWORD));
    }

    @Test
    void theFirstStepMethodComesBackWithThePendingSignIn() {
        final var token = pendingLoginStore.create(42L, false, SignInMethod.GITHUB);

        assertThat(pendingLoginStore.consume(token)).contains(new PendingLogin(42L, false, SignInMethod.GITHUB));
    }

    @Test
    void aPendingSignInStoredBeforeMethodsWereKeptComesBackWithoutOne() {
        redisTemplate.opsForValue().set(KEY_PREFIX + "legacy", "{\"userId\":42,\"rememberMe\":true}", Duration.ofMinutes(5));

        assertThat(pendingLoginStore.consume("legacy")).contains(new PendingLogin(42L, true, null));
    }

    @Test
    void consumeIsSingleUse() {
        final var token = pendingLoginStore.create(7L, false, SignInMethod.PASSWORD);

        assertThat(pendingLoginStore.consume(token)).contains(new PendingLogin(7L, false, SignInMethod.PASSWORD));
        assertThat(pendingLoginStore.consume(token)).isEmpty();
        assertThat(pendingLoginStore.find(token)).isEmpty();
    }

    @Test
    void discardRemovesTheToken() {
        final var token = pendingLoginStore.create(7L, false, SignInMethod.PASSWORD);

        pendingLoginStore.discard(token);

        assertThat(pendingLoginStore.find(token)).isEmpty();
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void anExpiredTokenIsGone() {
        final var token = pendingLoginStore.create(7L, false, SignInMethod.PASSWORD);

        redis.expireKeysContaining(KEY_PREFIX);

        assertThat(pendingLoginStore.find(token)).isEmpty();
        assertThat(pendingLoginStore.consume(token)).isEmpty();
    }

    @Test
    void eachTokenIsNew() {
        assertThat(pendingLoginStore.create(7L, false, SignInMethod.PASSWORD)).isNotEqualTo(pendingLoginStore.create(7L, false, SignInMethod.PASSWORD));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDownIsSignInUnavailable() {
        final ValueOperations<String, String> failingValueOperations = mock(ValueOperations.class);
        final var down = new RedisConnectionFailureException("down");
        when(redisTemplate.opsForValue()).thenReturn(failingValueOperations);
        doThrow(down).when(failingValueOperations).set(anyString(), anyString(), any(Duration.class));
        when(failingValueOperations.get(anyString())).thenThrow(down);
        when(failingValueOperations.getAndDelete(anyString())).thenThrow(down);
        when(redisTemplate.delete(anyString())).thenThrow(down);
        final var token = "a".repeat(43);

        for (final ThrowingCallable call : List.<ThrowingCallable>of(
                () -> pendingLoginStore.create(7L, false, SignInMethod.PASSWORD),
                () -> pendingLoginStore.find(token),
                () -> pendingLoginStore.consume(token),
                () -> pendingLoginStore.discard(token))) {
            assertThatThrownBy(call)
                    .isInstanceOf(SignInUnavailableException.class)
                    .hasMessage("Two-step sign-in isn't available right now. Try again in a minute.");
        }
    }

    @Test
    void aBlankTokenIsNeverLookedUp() {
        final var untouchedRedisTemplate = mock(StringRedisTemplate.class);
        final var store = new RedisPendingLoginStore(untouchedRedisTemplate, JSON);

        assertThat(store.find(null)).isEmpty();
        assertThat(store.find(" ")).isEmpty();
        assertThat(store.consume("")).isEmpty();
        store.discard(null);

        verifyNoInteractions(untouchedRedisTemplate);
    }

}
