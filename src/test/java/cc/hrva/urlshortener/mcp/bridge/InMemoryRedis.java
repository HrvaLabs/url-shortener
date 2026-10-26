package cc.hrva.urlshortener.mcp.bridge;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

public final class InMemoryRedis {

    private static final List<String> FIXED_WINDOW_COMMANDS = List.of("INCR", "PTTL", "PEXPIRE");

    private final Map<String, StoredValue> values = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public void backing(final StringRedisTemplate redisTemplate) {
        final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
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
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            final var arguments = invocation.getArguments();
            return fixedWindowIncrement(invocation.getArgument(0), invocation.getArgument(1), Arrays.copyOfRange(arguments, 2, arguments.length));
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

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Set<String> keys() {
        return Set.copyOf(values.keySet());
    }

    private Long fixedWindowIncrement(final RedisScript<?> script, final List<String> keys, final Object[] scriptArguments) {
        final var source = script.getScriptAsString();
        if (!FIXED_WINDOW_COMMANDS.stream().allMatch(source::contains) || keys.size() != 1 || scriptArguments.length != 1) {
            throw new UnsupportedOperationException("InMemoryRedis only models the fixed-window counter script");
        }
        final var window = Duration.ofMillis(Long.parseLong((String) scriptArguments[0]));

        final var counter = values.compute(keys.getFirst(), (key, current) -> current == null
                ? new StoredValue("1", window)
                : new StoredValue(Long.toString(Long.parseLong(current.value()) + 1),
                        current.timeToLive() == null ? window : current.timeToLive()));

        return Long.parseLong(counter.value());
    }

    private static String valueOf(final StoredValue storedValue) {
        return storedValue == null ? null : storedValue.value();
    }

    private record StoredValue(String value, Duration timeToLive) {
    }

}
