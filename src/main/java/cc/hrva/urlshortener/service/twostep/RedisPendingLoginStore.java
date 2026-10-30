package cc.hrva.urlshortener.service.twostep;

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
public class RedisPendingLoginStore implements PendingLoginStore {

    private static final String KEY_PREFIX = "auth:pending-login:";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public String create(final Long userId, final boolean rememberMe, final SignInMethod method) {
        final var token = SecureTokens.random();
        final var pendingLogin = jsonMapper.writeValueAsString(new PendingLogin(userId, rememberMe, method));
        inRedis(() -> {
            redisTemplate.opsForValue().set(KEY_PREFIX + token, pendingLogin, TIME_TO_LIVE);
            return null;
        });

        return token;
    }

    @Override
    public Optional<PendingLogin> find(final String token) {
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }

        return pendingLoginOf(inRedis(() -> redisTemplate.opsForValue().get(KEY_PREFIX + token)));
    }

    @Override
    public Optional<PendingLogin> consume(final String token) {
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }

        return pendingLoginOf(inRedis(() -> redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token)));
    }

    @Override
    public void discard(final String token) {
        if (StringUtils.isBlank(token)) {
            return;
        }

        inRedis(() -> redisTemplate.delete(KEY_PREFIX + token));
    }

    private Optional<PendingLogin> pendingLoginOf(final String storedPendingLogin) {
        return Optional.ofNullable(storedPendingLogin)
                .map(pendingLogin -> jsonMapper.readValue(pendingLogin, PendingLogin.class));
    }

    private static <T> T inRedis(final Supplier<T> command) {
        try {
            return command.get();
        } catch (final DataAccessException exception) {
            log.warn("Pending sign-in store unavailable exception={}", exception.getClass().getName());
            throw new SignInUnavailableException(UNAVAILABLE, exception);
        }
    }

}
