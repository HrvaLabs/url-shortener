package cc.hrva.urlshortener.mcp.bridge;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class InMemoryRedis {

    private final Map<String, StoredValue> values = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    void backing(final StringRedisTemplate redisTemplate) {
        final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), new StoredValue(invocation.getArgument(1), invocation.getArgument(2)));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation ->
                values.putIfAbsent(invocation.getArgument(0), new StoredValue(invocation.getArgument(1), invocation.getArgument(2))) == null);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> valueOf(values.get(invocation.<String>getArgument(0))));
        when(valueOperations.getAndDelete(anyString())).thenAnswer(invocation -> valueOf(values.remove(invocation.<String>getArgument(0))));
        when(redisTemplate.delete(anyString())).thenAnswer(invocation -> values.remove(invocation.<String>getArgument(0)) != null);
    }

    Duration timeToLiveOfKeyContaining(final String keyPart) {
        final var matchingValues = values.entrySet().stream()
                .filter(entry -> entry.getKey().contains(keyPart))
                .map(Map.Entry::getValue)
                .toList();
        assertThat(matchingValues).hasSize(1);

        return matchingValues.getFirst().timeToLive();
    }

    void expireKeysContaining(final String keyPart) {
        values.keySet().removeIf(key -> key.contains(keyPart));
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    private static String valueOf(final StoredValue storedValue) {
        return storedValue == null ? null : storedValue.value();
    }

    private record StoredValue(String value, Duration timeToLive) {
    }

}
