package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.exception.SignInUnavailableException;
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
public class RedisPasskeyCeremonyStore implements PasskeyCeremonyStore {

    private static final String KEY_PREFIX = "passkey:ceremony:";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public String save(final PasskeyCeremony ceremony) {
        final var ceremonyId = SecureTokens.random();
        final var storedCeremony = jsonMapper.writeValueAsString(ceremony);
        inRedis(() -> {
            redisTemplate.opsForValue().set(KEY_PREFIX + ceremonyId, storedCeremony, TIME_TO_LIVE);
            return null;
        });

        return ceremonyId;
    }

    @Override
    public Optional<PasskeyCeremony> consume(final String ceremonyId) {
        if (StringUtils.isBlank(ceremonyId)) {
            return Optional.empty();
        }

        return Optional.ofNullable(inRedis(() -> redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ceremonyId)))
                .map(storedCeremony -> jsonMapper.readValue(storedCeremony, PasskeyCeremony.class));
    }

    private static <T> T inRedis(final Supplier<T> command) {
        try {
            return command.get();
        } catch (final DataAccessException exception) {
            log.warn("Passkey ceremony store unavailable exception={}", exception.getClass().getName());
            throw new SignInUnavailableException(UNAVAILABLE, exception);
        }
    }

}
