package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.dto.BlocklistStatus;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlocklistHealthTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final String KEY = "blocklist-health:1";

    private final Map<String, Map<Object, Object>> hashes = new HashMap<>();
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashOperations = mock();
    private final BlocklistHealth blocklistHealth = new BlocklistHealth(redisTemplate,
            Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(anyString())).thenAnswer(invocation -> Map.copyOf(hash(invocation.getArgument(0))));
        doAnswer(invocation -> {
            hash(invocation.getArgument(0)).putAll(invocation.getArgument(1));
            return null;
        }).when(hashOperations).putAll(anyString(), any());
        when(hashOperations.increment(anyString(), any(), anyLong())).thenAnswer(invocation -> {
            final var hash = hash(invocation.getArgument(0));
            final var next = Long.parseLong((String) hash.getOrDefault(invocation.getArgument(1), "0")) + invocation.<Long>getArgument(2);
            hash.put(invocation.getArgument(1), Long.toString(next));
            return next;
        });
        when(hashOperations.putIfAbsent(anyString(), any(), any()))
                .thenAnswer(invocation -> hash(invocation.getArgument(0)).putIfAbsent(invocation.getArgument(1), invocation.getArgument(2)) == null);
        when(hashOperations.delete(anyString(), any())).thenAnswer(invocation -> {
            final var hash = hash(invocation.getArgument(0));
            return hash.remove(invocation.getArgument(1)) == null ? 0L : 1L;
        });
    }

    @Test
    void aRefreshRecordsItsTimeAndClearsTheErrors() {
        blocklistHealth.failed(1);
        blocklistHealth.failed(1);

        assertThat(blocklistHealth.statusOf(source().build()).consecutiveErrors()).isEqualTo(2);

        blocklistHealth.refreshed(1);

        assertThat(blocklistHealth.statusOf(source().build())).isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, NOW, 0, false));
    }

    @Test
    void aListIsStaleAfterTwoMissedRefreshPeriods() {
        hash(KEY).put("refreshed-at", NOW.minusHours(12).minusMinutes(1).toString());

        assertThat(blocklistHealth.statusOf(source().build()).stale()).isTrue();

        hash(KEY).put("refreshed-at", NOW.minusHours(11).toString());

        assertThat(blocklistHealth.statusOf(source().build()).stale()).isFalse();
    }

    @Test
    void aDisabledListIsNeverStale() {
        hash(KEY).put("refreshed-at", NOW.minusDays(5).toString());

        assertThat(blocklistHealth.statusOf(source().enabled(false).build()).stale()).isFalse();
    }

    @Test
    void withoutARecordedRefreshTheLastFetchCountsWhenItHadNoError() {
        final var unchanged = source().lastFetchedAt(NOW.minusHours(1)).lastSuccessAt(NOW.minusDays(3)).build();
        final var failing = source().lastFetchedAt(NOW.minusHours(1)).lastSuccessAt(NOW.minusDays(3)).lastError("HTTP 503").build();

        assertThat(blocklistHealth.statusOf(unchanged)).isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, NOW.minusHours(1), 0, false));
        assertThat(blocklistHealth.statusOf(failing)).isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, NOW.minusDays(3), 0, true));
    }

    @Test
    void aListThatNeverLoadedIsStaleAfterTwoErrorsInARow() {
        blocklistHealth.failed(1);

        assertThat(blocklistHealth.statusOf(source().build()).stale()).isFalse();

        blocklistHealth.failed(1);

        assertThat(blocklistHealth.statusOf(source().build())).isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, null, 2, true));
    }

    @Test
    void theStaleAlertIsClaimedOnceUntilTheListRefreshes() {
        assertThat(blocklistHealth.claimStaleAlert(1)).isTrue();
        assertThat(blocklistHealth.claimStaleAlert(1)).isFalse();

        blocklistHealth.refreshed(1);

        assertThat(blocklistHealth.claimStaleAlert(1)).isTrue();
    }

    @Test
    void aRedisThatIsDownFallsBackToTheDatabaseAndClaimsNoAlert() {
        when(hashOperations.entries(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(hashOperations.putIfAbsent(anyString(), any(), any())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(blocklistHealth.statusOf(source().lastFetchedAt(NOW.minusHours(2)).build()))
                .isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, NOW.minusHours(2), 0, false));
        assertThat(blocklistHealth.claimStaleAlert(1)).isFalse();
    }

    @Test
    void theStatusFromTheDatabaseReadsNothingFromRedis() {
        blocklistHealth.refreshed(1);
        clearInvocations(redisTemplate, hashOperations);
        final var fresh = source().lastFetchedAt(NOW.minusHours(2)).build();
        final var old = source().lastFetchedAt(NOW.minusHours(13)).lastSuccessAt(NOW.minusHours(13)).build();

        assertThat(blocklistHealth.statusFromDatabase(fresh)).isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, NOW.minusHours(2), 0, false));
        assertThat(blocklistHealth.statusFromDatabase(old)).isEqualTo(new BlocklistStatus(1, "HaGeZi Fake", 6, NOW.minusHours(13), 0, true));
        verifyNoInteractions(redisTemplate, hashOperations);
    }

    private Map<Object, Object> hash(final String key) {
        return hashes.computeIfAbsent(key, ignored -> new HashMap<>());
    }

    private static BlocklistSource.BlocklistSourceBuilder source() {
        return BlocklistSource.builder().id(1L).name("HaGeZi Fake").url("https://example.com/list.txt").action(BlocklistAction.BLOCK);
    }

}
