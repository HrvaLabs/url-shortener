package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.dto.BlocklistStatus;
import cc.hrva.urlshortener.model.BlocklistSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlocklistHealth {

    private static final String KEY_PREFIX = "blocklist-health:";
    private static final String REFRESHED_AT = "refreshed-at";
    private static final String ERRORS = "errors";
    private static final String STALE_ALERTED = "stale-alerted";
    private static final int MISSED_PERIODS = 2;

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public void refreshed(final long sourceId) {
        try {
            redisTemplate.opsForHash().putAll(keyOf(sourceId), Map.of(REFRESHED_AT, LocalDateTime.now(clock).toString(), ERRORS, "0"));
            redisTemplate.opsForHash().delete(keyOf(sourceId), STALE_ALERTED);
        } catch (final RuntimeException exception) {
            log.warn("Could not record the refresh of blocklist source {} exception={}", sourceId, exception.getClass().getName());
        }
    }

    public void failed(final long sourceId) {
        try {
            redisTemplate.opsForHash().increment(keyOf(sourceId), ERRORS, 1);
        } catch (final RuntimeException exception) {
            log.warn("Could not count the failed refresh of blocklist source {} exception={}", sourceId, exception.getClass().getName());
        }
    }

    public BlocklistStatus statusOf(final BlocklistSource source) {
        return statusOf(source, recorded(source.getId()));
    }

    public BlocklistStatus statusFromDatabase(final BlocklistSource source) {
        return statusOf(source, Map.of());
    }

    private BlocklistStatus statusOf(final BlocklistSource source, final Map<Object, Object> recorded) {
        final var lastRefreshedAt = Optional.ofNullable(recorded.get(REFRESHED_AT)).map(value -> LocalDateTime.parse(value.toString()))
                .orElseGet(() -> source.getLastError() == null ? source.getLastFetchedAt() : source.getLastSuccessAt());
        final var errors = NumberUtils.toLong(String.valueOf(recorded.get(ERRORS)));
        final var stale = source.isEnabled() && (lastRefreshedAt == null
                ? errors >= MISSED_PERIODS
                : lastRefreshedAt.isBefore(LocalDateTime.now(clock).minusHours((long) MISSED_PERIODS * source.getRefreshIntervalHours())));
        return new BlocklistStatus(source.getId(), source.getName(), source.getRefreshIntervalHours(), lastRefreshedAt, errors, stale);
    }

    public boolean claimStaleAlert(final long sourceId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForHash().putIfAbsent(keyOf(sourceId), STALE_ALERTED, "1"));
        } catch (final RuntimeException exception) {
            log.warn("Could not record the stale alert of blocklist source {} exception={}", sourceId, exception.getClass().getName());
            return false;
        }
    }

    public void forget(final long sourceId) {
        try {
            redisTemplate.delete(keyOf(sourceId));
        } catch (final RuntimeException exception) {
            log.warn("Could not forget the health of blocklist source {} exception={}", sourceId, exception.getClass().getName());
        }
    }

    private Map<Object, Object> recorded(final long sourceId) {
        try {
            return redisTemplate.opsForHash().entries(keyOf(sourceId));
        } catch (final RuntimeException exception) {
            log.warn("Could not read the health of blocklist source {} exception={}", sourceId, exception.getClass().getName());
            return Map.of();
        }
    }

    private static String keyOf(final long sourceId) {
        return KEY_PREFIX + sourceId;
    }

}
