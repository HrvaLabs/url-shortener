package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.dto.ClickRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UniqueVisitorCounter {

    private static final String SALT_KEY_PREFIX = "analytics:salt:";
    private static final String VISITORS_KEY_PREFIX = "analytics:visitors:";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int SALT_BYTES = 32;
    private static final int HASH_BYTES = 16;
    private static final long SEEN = 0L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final RedisScript<Long> ADD_WITH_EXPIRY = RedisScript.of("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('PEXPIREAT', KEYS[1], ARGV[2])
            return added
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AnalyticsDays analyticsDays;
    private final Clock clock;

    public boolean isNewVisitor(final long urlId, final LocalDate day, final ClickRequest click) {
        try {
            final var expiry = analyticsDays.saltExpiry(day);
            final var visitorsKey = VISITORS_KEY_PREFIX + urlId + ":" + day;
            final var hash = visitorHash(saltOf(day, expiry), click, urlId);
            final var added = redisTemplate.execute(ADD_WITH_EXPIRY, List.of(visitorsKey), hash, Long.toString(expiry.toEpochMilli()));
            return added == null || added != SEEN;
        } catch (final RuntimeException exception) {
            log.warn("Unique visitor check unavailable, counting a new visitor urlId={} exception={}", urlId, exception.getClass().getName());
            return true;
        }
    }

    public static String visitorHash(final byte[] salt, final ClickRequest click, final long urlId) {
        final var visitor = StringUtils.defaultString(click.clientIp()) + "\n" + StringUtils.defaultString(click.userAgent()) + "\n" + urlId;
        final var hmac = hmacSha256(salt).doFinal(visitor.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hmac, HASH_BYTES));
    }

    private byte[] saltOf(final LocalDate day, final Instant expiry) {
        final var saltKey = SALT_KEY_PREFIX + day;
        final var stored = redisTemplate.opsForValue().get(saltKey);
        final var salt = stored != null ? stored : createSalt(saltKey, expiry);
        if (salt == null) {
            throw new IllegalStateException("The day's salt could not be read or created");
        }

        return Base64.getDecoder().decode(salt);
    }

    private String createSalt(final String saltKey, final Instant expiry) {
        final var timeToLive = Duration.between(clock.instant(), expiry);
        if (!timeToLive.isPositive()) {
            throw new IllegalStateException("The day's salt has already expired");
        }

        final var bytes = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        final var salt = Base64.getEncoder().encodeToString(bytes);
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(saltKey, salt, timeToLive))) {
            return salt;
        }

        return redisTemplate.opsForValue().get(saltKey);
    }

    private static Mac hmacSha256(final byte[] key) {
        try {
            final var mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac;
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
