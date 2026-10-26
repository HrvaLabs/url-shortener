package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.web.webauthn.api.Bytes;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisPasskeyCeremonyStoreTest {

    private static final String KEY_PREFIX = "passkey:ceremony:";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CHALLENGE = Bytes.random().toBase64UrlString();

    private final InMemoryRedis redis = new InMemoryRedis();

    private StringRedisTemplate redisTemplate;
    private RedisPasskeyCeremonyStore passkeyCeremonyStore;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        passkeyCeremonyStore = new RedisPasskeyCeremonyStore(redisTemplate, JSON);
    }

    @Test
    void savesForFiveMinutesUnderPasskeyCeremony() {
        final var ceremonyId = passkeyCeremonyStore.save(new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, CHALLENGE, null));

        assertThat(ceremonyId).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(redis.keys()).containsExactly(KEY_PREFIX + ceremonyId);
        assertThat(redis.timeToLiveOfKeyContaining(KEY_PREFIX)).isEqualTo(Duration.ofMinutes(5));
        assertThat(JSON.readTree(redisTemplate.opsForValue().get(KEY_PREFIX + ceremonyId)))
                .isEqualTo(JSON.readTree("{\"purpose\":\"SIGN_IN\",\"challenge\":\"" + CHALLENGE + "\",\"userId\":null}"));
    }

    @Test
    void consumeIsSingleUse() {
        final var ceremony = new PasskeyCeremony(PasskeyCeremonyPurpose.REGISTRATION, CHALLENGE, 7L);
        final var ceremonyId = passkeyCeremonyStore.save(ceremony);

        assertThat(passkeyCeremonyStore.consume(ceremonyId)).contains(ceremony);
        assertThat(passkeyCeremonyStore.consume(ceremonyId)).isEmpty();
    }

    @Test
    void anExpiredCeremonyIsGone() {
        final var ceremonyId = passkeyCeremonyStore.save(new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, CHALLENGE, 7L));

        redis.expireKeysContaining(KEY_PREFIX);

        assertThat(passkeyCeremonyStore.consume(ceremonyId)).isEmpty();
    }

    @Test
    void eachCeremonyIdIsNew() {
        final var ceremony = new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, CHALLENGE, null);

        assertThat(passkeyCeremonyStore.save(ceremony)).isNotEqualTo(passkeyCeremonyStore.save(ceremony));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDownIsA503() {
        final ValueOperations<String, String> failingValueOperations = mock(ValueOperations.class);
        final var down = new RedisConnectionFailureException("down");
        when(redisTemplate.opsForValue()).thenReturn(failingValueOperations);
        doThrow(down).when(failingValueOperations).set(anyString(), anyString(), any(Duration.class));
        when(failingValueOperations.getAndDelete(anyString())).thenThrow(down);

        for (final ThrowingCallable call : List.<ThrowingCallable>of(
                () -> passkeyCeremonyStore.save(new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, CHALLENGE, null)),
                () -> passkeyCeremonyStore.consume("a".repeat(43)))) {
            assertThatThrownBy(call)
                    .isInstanceOf(SignInUnavailableException.class)
                    .hasMessage("Passkeys aren't available right now. Try again in a minute.");
        }
    }

    @Test
    void aBlankCeremonyIdIsNeverLookedUp() {
        final var untouchedRedisTemplate = mock(StringRedisTemplate.class);
        final var store = new RedisPasskeyCeremonyStore(untouchedRedisTemplate, JSON);

        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume(" ")).isEmpty();

        verifyNoInteractions(untouchedRedisTemplate);
    }

}
