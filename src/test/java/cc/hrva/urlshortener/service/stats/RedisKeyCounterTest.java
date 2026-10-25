package cc.hrva.urlshortener.service.stats;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisKeyCounterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private Cursor<String> cursor;

    private RedisKeyCounter redisKeyCounter;

    @BeforeEach
    void setUp() {
        this.redisKeyCounter = new RedisKeyCounter(redisTemplate);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
    }

    @Test
    void countsKeysByPrefix() {
        givenKeys(List.of("blocklist:source:1", "rate-limit:create:anon:x", "analytics:salt:2026-10-25", "urls::abc", "spring:session:x"));

        final var keys = redisKeyCounter.count();

        assertThat(keys.counts()).containsExactly(
                entry("blocklist:", 1L),
                entry("rate-limit:", 1L),
                entry("analytics:", 1L),
                entry("rdap:", 0L),
                entry("dbl:", 0L),
                entry("mcp:connect:", 0L),
                entry("urls::", 1L));
        assertThat(keys.other()).isEqualTo(1);
        assertThat(keys.scanned()).isEqualTo(5);
        assertThat(keys.capped()).isFalse();
        final var options = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisTemplate).scan(options.capture());
        assertThat(options.getValue().getCount()).isEqualTo(1_000L);
        assertThat(options.getValue().getPattern()).isNull();
    }

    @Test
    void stopsAtTheCapAndSaysSo() {
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn("spring:session:x");

        final var keys = redisKeyCounter.count();

        assertThat(keys.scanned()).isEqualTo(100_000);
        assertThat(keys.other()).isEqualTo(100_000);
        assertThat(keys.capped()).isTrue();
    }

    @Test
    void exactlyTheCapWithNothingLeftIsNotCapped() {
        givenKeys(IntStream.range(0, 100_000).mapToObj(index -> "rdap:example-" + index + ".com").toList());

        final var keys = redisKeyCounter.count();

        assertThat(keys.scanned()).isEqualTo(100_000);
        assertThat(keys.counts()).containsEntry("rdap:", 100_000L);
        assertThat(keys.capped()).isFalse();
    }

    @Test
    void neverUsesKeys() {
        givenKeys(List.of("dbl:example.com", "mcp:connect:request:x"));

        redisKeyCounter.count();

        verify(redisTemplate, never()).keys(any());
    }

    @Test
    void closesTheCursor() {
        givenKeys(List.of("urls::abc"));

        redisKeyCounter.count();

        verify(cursor).close();
    }

    @Test
    void aFailingScanPropagatesAndStillClosesTheCursor() {
        final var failure = new RedisSystemException("Redis went away", new IllegalStateException());
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenThrow(failure);

        assertThatThrownBy(() -> redisKeyCounter.count()).isSameAs(failure);
        verify(cursor).close();
    }

    private void givenKeys(final List<String> keys) {
        final var iterator = keys.iterator();
        when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(cursor.next()).thenAnswer(invocation -> iterator.next());
    }

}
