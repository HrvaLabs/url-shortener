package cc.hrva.urlshortener.service.blocklist;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlocklistStore {

    private static final String KEY_PREFIX = "blocklist:source:";
    private static final String LOADING_SUFFIX = ":loading";
    private static final int BATCH_SIZE = 10_000;
    private static final Duration LOADING_TTL = Duration.ofMinutes(2);
    private static final RedisScript<Long> MEMORY_USAGE = RedisScript.of("return redis.call('MEMORY', 'USAGE', KEYS[1])", Long.class);
    private static final RedisScript<Long> RENAME_IF_COMPLETE = RedisScript.of("""
            local size = redis.call('SCARD', KEYS[1])
            if size ~= tonumber(ARGV[1]) then
                return redis.error_reply('incomplete blocklist load: ' .. size .. ' of ' .. ARGV[1] .. ' entries')
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('PERSIST', KEYS[2])
            return size
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public void replace(final long sourceId, final Set<String> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("A blocklist set is never replaced with an empty one");
        }
        final var loadingKey = loadingKeyOf(sourceId);
        redisTemplate.delete(loadingKey);
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                addInBatches(connection, bytesOf(loadingKey), entries);
                return null;
            });
            redisTemplate.execute(RENAME_IF_COMPLETE, List.of(loadingKey, keyOf(sourceId)), String.valueOf(entries.size()));
        } catch (final RuntimeException exception) {
            discard(loadingKey);
            throw exception;
        }
    }

    public void remove(final long sourceId) {
        try {
            redisTemplate.delete(List.of(keyOf(sourceId), loadingKeyOf(sourceId)));
        } catch (final DataAccessException exception) {
            log.warn("Could not remove the Redis set of blocklist source {} exception={}", sourceId, exception.getClass().getName());
        }
    }

    public boolean exists(final long sourceId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(keyOf(sourceId)));
    }

    public Long memoryBytes(final long sourceId) {
        try {
            return redisTemplate.execute(MEMORY_USAGE, List.of(keyOf(sourceId)));
        } catch (final DataAccessException exception) {
            log.warn("Could not read the Redis memory of blocklist source {} exception={}", sourceId, exception.getClass().getName());
            return null;
        }
    }

    public RedisMemory memory() {
        final var info = redisTemplate.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("memory"));
        if (info == null) {
            throw new IllegalStateException("Redis returned no memory information");
        }
        return new RedisMemory(longOf(info, "used_memory"), longOf(info, "maxmemory"));
    }

    public Set<Long> matchingSources(final Map<Long, List<String>> keysBySource) {
        final var lookups = keysBySource.entrySet().stream().filter(lookup -> !lookup.getValue().isEmpty()).toList();
        if (lookups.isEmpty()) {
            return Set.of();
        }
        try {
            final var answers = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                lookups.forEach(lookup -> connection.setCommands().sMIsMember(bytesOf(keyOf(lookup.getKey())), bytesOf(lookup.getValue())));
                return null;
            });
            final var matches = new HashSet<Long>();
            for (int index = 0; index < lookups.size(); index++) {
                if (((List<?>) answers.get(index)).contains(Boolean.TRUE)) {
                    matches.add(lookups.get(index).getKey());
                }
            }
            return matches;
        } catch (final DataAccessException exception) {
            log.warn("Blocklist lookup failed, skipping the source lists exception={}", exception.getClass().getName());
            return Set.of();
        }
    }

    private static void addInBatches(final RedisConnection connection, final byte[] key, final Set<String> entries) {
        final var batch = new ArrayList<String>(BATCH_SIZE);
        for (final var entry : entries) {
            batch.add(entry);
            if (batch.size() == BATCH_SIZE) {
                addExpiring(connection, key, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            addExpiring(connection, key, batch);
        }
    }

    private static void addExpiring(final RedisConnection connection, final byte[] key, final List<String> batch) {
        connection.setCommands().sAdd(key, bytesOf(batch));
        connection.keyCommands().expire(key, LOADING_TTL.toSeconds());
    }

    private void discard(final String loadingKey) {
        try {
            redisTemplate.delete(loadingKey);
        } catch (final DataAccessException exception) {
            log.warn("Could not delete the partial blocklist set {} exception={}", loadingKey, exception.getClass().getName());
        }
    }

    private static long longOf(final Properties info, final String field) {
        return Long.parseLong(info.getProperty(field, "0").strip());
    }

    private static String keyOf(final long sourceId) {
        return KEY_PREFIX + sourceId;
    }

    private static String loadingKeyOf(final long sourceId) {
        return keyOf(sourceId) + LOADING_SUFFIX;
    }

    private static byte[] bytesOf(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[][] bytesOf(final List<String> values) {
        return values.stream().map(BlocklistStore::bytesOf).toArray(byte[][]::new);
    }

    public record RedisMemory(long usedMemory, long maxMemory) {}

}
