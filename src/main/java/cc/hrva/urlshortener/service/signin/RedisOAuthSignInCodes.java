package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.security.SecureTokens;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOAuthSignInCodes implements OAuthSignInCodes {

    private static final String KEY_PREFIX = "auth:oauth-code:";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public String create(final Long userId, final SignInMethod method) {
        final var code = SecureTokens.random();
        final var signIn = jsonMapper.writeValueAsString(new OAuthSignIn(userId, method));
        inRedis(() -> {
            redisTemplate.opsForValue().set(KEY_PREFIX + code, signIn, TIME_TO_LIVE);
            return null;
        });

        return code;
    }

    @Override
    public Optional<OAuthSignIn> consume(final String code) {
        if (StringUtils.isBlank(code)) {
            return Optional.empty();
        }

        return Optional.ofNullable(inRedis(() -> redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code)))
                .map(signIn -> jsonMapper.readValue(signIn, OAuthSignIn.class));
    }

    private static <T> T inRedis(final Supplier<T> command) {
        try {
            return command.get();
        } catch (final DataAccessException exception) {
            log.warn("OAuth sign-in code store unavailable exception={}", exception.getClass().getName());
            throw new SignInUnavailableException(UNAVAILABLE, exception);
        }
    }

}
