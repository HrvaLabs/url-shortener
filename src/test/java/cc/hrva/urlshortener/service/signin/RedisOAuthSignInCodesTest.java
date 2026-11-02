package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisOAuthSignInCodesTest {

    private static final String KEY_PREFIX = "auth:oauth-code:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InMemoryRedis redis = new InMemoryRedis();

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private StringRedisTemplate redisTemplate;
    private RedisOAuthSignInCodes codes;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        codes = new RedisOAuthSignInCodes(redisTemplate, JSON);
    }

    @Test
    void aCodeIsRandomAndLivesSixtySeconds() {
        final var code = codes.create(42L, SignInMethod.GITHUB, CHALLENGE);

        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(codes.create(42L, SignInMethod.GITHUB, CHALLENGE)).isNotEqualTo(code);
        assertThat(redis.keys()).contains(KEY_PREFIX + code);
        assertThat(redis.timeToLiveOfKeyContaining(code)).isEqualTo(Duration.ofSeconds(60));
        assertThat(JSON.readTree(redisTemplate.opsForValue().get(KEY_PREFIX + code)))
                .isEqualTo(JSON.readTree("{\"userId\":42,\"method\":\"GITHUB\",\"challenge\":\"" + CHALLENGE + "\"}"));
    }

    @Test
    void aCodeWorksOnce() {
        final var code = codes.create(42L, SignInMethod.GOOGLE, CHALLENGE);

        assertThat(codes.consume(code)).contains(new OAuthSignIn(42L, SignInMethod.GOOGLE, CHALLENGE));
        assertThat(codes.consume(code)).isEmpty();
    }

    @Test
    void anUnknownOrBlankCodeIsNothing() {
        assertThat(codes.consume("made-up")).isEmpty();
        assertThat(codes.consume(" ")).isEmpty();
        assertThat(codes.consume(null)).isEmpty();
    }

    @Test
    void aMissingRedisMakesSigningInUnavailable() {
        when(redisTemplate.opsForValue().getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> codes.consume("code"))
                .isInstanceOf(SignInUnavailableException.class)
                .hasMessage("Signing in isn't available right now. Try again in a minute.");
    }

    @Test
    void aCodeStoredBeforeChallengesReadsWithoutOne() {
        redisTemplate.opsForValue().set(KEY_PREFIX + "old", "{\"userId\":42,\"method\":\"GOOGLE\"}", Duration.ofSeconds(60));

        assertThat(codes.consume("old")).contains(new OAuthSignIn(42L, SignInMethod.GOOGLE, null));
    }

}
