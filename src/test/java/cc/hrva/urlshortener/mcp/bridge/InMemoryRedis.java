package cc.hrva.urlshortener.mcp.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

public final class InMemoryRedis {

    private static final List<String> FIXED_WINDOW_COMMANDS = List.of("INCR", "PTTL", "PEXPIRE");
    private static final List<String> ADD_WITH_EXPIRY_COMMANDS = List.of("SADD", "PEXPIREAT");

    private final Map<String, StoredValue> values = new ConcurrentHashMap<>();
    private final Map<String, StoredSet> sets = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public void backing(final StringRedisTemplate redisTemplate) {
        final ValueOperations<String, String> valueOperations = mock(ValueOperations.class, withSettings().strictness(Strictness.LENIENT));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().doAnswer(invocation -> {
            values.put(invocation.getArgument(0), new StoredValue(invocation.getArgument(1), invocation.getArgument(2)));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation ->
                values.putIfAbsent(invocation.getArgument(0), new StoredValue(invocation.getArgument(1), invocation.getArgument(2))) == null);
        lenient().when(valueOperations.get(anyString())).thenAnswer(invocation -> valueOf(values.get(invocation.<String>getArgument(0))));
        lenient().when(valueOperations.getAndDelete(anyString())).thenAnswer(invocation -> valueOf(values.remove(invocation.<String>getArgument(0))));
        lenient().when(redisTemplate.delete(anyString())).thenAnswer(invocation -> values.remove(invocation.<String>getArgument(0)) != null);
        lenient().when(redisTemplate.delete(anyCollection())).thenAnswer(invocation -> invocation.<Collection<String>>getArgument(0).stream()
                .filter(key -> values.remove(key) != null)
                .count());
        lenient().when(valueOperations.multiGet(anyCollection())).thenAnswer(invocation -> invocation.<Collection<String>>getArgument(0).stream()
                .map(key -> valueOf(values.get(key)))
                .toList());
        lenient().when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(invocation -> {
            final var stored = values.get(invocation.<String>getArgument(0));
            if (stored == null) {
                return -2L;
            }
            return stored.timeToLive() == null ? -1L : invocation.<TimeUnit>getArgument(1).convert(stored.timeToLive());
        });
        lenient().when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> cursorOver(
                keysMatching(invocation.<ScanOptions>getArgument(0).getPattern())));
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            final var arguments = invocation.getArguments();
            final RedisScript<?> script = invocation.getArgument(0);
            final List<String> keys = invocation.getArgument(1);
            final var scriptArguments = Arrays.copyOfRange(arguments, 2, arguments.length);
            if (ADD_WITH_EXPIRY_COMMANDS.stream().allMatch(script.getScriptAsString()::contains)) {
                return addWithExpiry(keys, scriptArguments);
            }
            return fixedWindowIncrement(script, keys, scriptArguments);
        });
    }

    public Duration timeToLiveOfKeyContaining(final String keyPart) {
        final var matchingValues = values.entrySet().stream()
                .filter(entry -> entry.getKey().contains(keyPart))
                .map(Map.Entry::getValue)
                .toList();
        assertThat(matchingValues).hasSize(1);

        return matchingValues.getFirst().timeToLive();
    }

    public void expireKeysContaining(final String keyPart) {
        values.keySet().removeIf(key -> key.contains(keyPart));
    }

    public Set<String> membersOf(final String key) {
        final var set = sets.get(key);
        return set == null ? Set.of() : Set.copyOf(set.members());
    }

    public Instant expiryOf(final String key) {
        assertThat(sets).containsKey(key);
        return sets.get(key).expiresAt();
    }

    public boolean isEmpty() {
        return values.isEmpty() && sets.isEmpty();
    }

    public Set<String> keys() {
        return Set.copyOf(Stream.concat(values.keySet().stream(), sets.keySet().stream()).toList());
    }

    private Long addWithExpiry(final List<String> keys, final Object[] scriptArguments) {
        if (keys.size() != 1 || scriptArguments.length != 2) {
            throw new UnsupportedOperationException("InMemoryRedis models SADD and PEXPIREAT on one key with a member and an expiry");
        }
        final var member = (String) scriptArguments[0];
        final var expiresAt = Instant.ofEpochMilli(Long.parseLong((String) scriptArguments[1]));
        final var set = sets.computeIfAbsent(keys.getFirst(), key -> new StoredSet(ConcurrentHashMap.newKeySet(), expiresAt));
        final var added = set.members().add(member);
        sets.put(keys.getFirst(), new StoredSet(set.members(), expiresAt));

        return added ? 1L : 0L;
    }

    private Object fixedWindowIncrement(final RedisScript<?> script, final List<String> keys, final Object[] scriptArguments) {
        final var source = script.getScriptAsString();
        if (!FIXED_WINDOW_COMMANDS.stream().allMatch(source::contains) || keys.size() != 1 || scriptArguments.length != 1) {
            throw new UnsupportedOperationException("InMemoryRedis only models the fixed-window counter script");
        }
        final var window = Duration.ofMillis(Long.parseLong((String) scriptArguments[0]));

        final var counter = values.compute(keys.getFirst(), (key, current) -> current == null
                ? new StoredValue("1", window)
                : new StoredValue(Long.toString(Long.parseLong(current.value()) + 1),
                        current.timeToLive() == null ? window : current.timeToLive()));

        if (List.class.equals(script.getResultType())) {
            return List.of(Long.parseLong(counter.value()), counter.timeToLive().toMillis());
        }
        return Long.parseLong(counter.value());
    }

    private List<String> keysMatching(final String pattern) {
        final var regex = pattern == null ? ".*" : Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return values.keySet().stream().filter(key -> key.matches(regex)).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> cursorOver(final List<String> keys) {
        final var iterator = keys.iterator();
        final Cursor<String> cursor = mock(Cursor.class, withSettings().strictness(Strictness.LENIENT));
        lenient().when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        lenient().when(cursor.next()).thenAnswer(invocation -> iterator.next());
        return cursor;
    }

    private static String valueOf(final StoredValue storedValue) {
        return storedValue == null ? null : storedValue.value();
    }

    private record StoredValue(String value, Duration timeToLive) {
    }

    private record StoredSet(Set<String> members, Instant expiresAt) {
    }

}
