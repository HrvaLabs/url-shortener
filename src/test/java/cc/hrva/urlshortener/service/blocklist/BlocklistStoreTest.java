package cc.hrva.urlshortener.service.blocklist;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlocklistStoreTest {

    private static final String LIVE_KEY = "blocklist:source:7";
    private static final String LOADING_KEY = "blocklist:source:7:loading";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnection connection;

    @Mock
    private RedisSetCommands setCommands;

    @Mock
    private RedisServerCommands serverCommands;

    @Mock
    private RedisKeyCommands keyCommands;

    @Captor
    private ArgumentCaptor<RedisScript<Long>> renameScript;

    private BlocklistStore blocklistStore;

    @BeforeEach
    void setUp() {
        blocklistStore = new BlocklistStore(redisTemplate);
    }

    @Test
    void replaceLoadsAFreshLoadingKeyInBatchesOfTenThousandThenRenamesItOverTheLiveSet() {
        final var entries = domains(25_000);
        when(connection.setCommands()).thenReturn(setCommands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenAnswer(pipelineReturning(List.of(10_000L, true, 10_000L, true, 5_000L, true)));

        blocklistStore.replace(7, entries);

        final ArgumentCaptor<byte[][]> batches = ArgumentCaptor.forClass(byte[][].class);
        verify(setCommands, times(3)).sAdd(eq(bytes(LOADING_KEY)), batches.capture());
        assertThat(batches.getAllValues()).extracting(batch -> batch.length).containsExactly(10_000, 10_000, 5_000);
        assertThat(batches.getAllValues().stream().flatMap(Arrays::stream).map(BlocklistStoreTest::string))
                .containsExactlyInAnyOrderElementsOf(entries);
        final InOrder order = inOrder(redisTemplate);
        order.verify(redisTemplate).delete(LOADING_KEY);
        order.verify(redisTemplate).executePipelined(any(RedisCallback.class));
        order.verify(redisTemplate).execute(renameScript.capture(), eq(List.of(LOADING_KEY, LIVE_KEY)), eq("25000"));
        assertThat(renameScript.getValue().getScriptAsString())
                .contains("redis.call('SCARD', KEYS[1])")
                .contains("if size ~= tonumber(ARGV[1]) then")
                .contains("redis.call('RENAME', KEYS[1], KEYS[2])")
                .contains("redis.call('PERSIST', KEYS[2])");
    }

    @Test
    void theLoadingKeyExpiresAfterEveryBatchSoAnAbandonedLoadCleansItselfUp() {
        when(connection.setCommands()).thenReturn(setCommands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenAnswer(pipelineReturning(List.of(10_000L, true, 5_000L, true)));

        blocklistStore.replace(7, domains(15_000));

        final InOrder order = inOrder(setCommands, keyCommands);
        order.verify(setCommands).sAdd(eq(bytes(LOADING_KEY)), any(byte[][].class));
        order.verify(keyCommands).expire(bytes(LOADING_KEY), 600);
        order.verify(setCommands).sAdd(eq(bytes(LOADING_KEY)), any(byte[][].class));
        order.verify(keyCommands).expire(bytes(LOADING_KEY), 600);
    }

    @Test
    void aFailedLoadNeverRenamesAndDropsTheLoadingKey() {
        when(connection.setCommands()).thenReturn(setCommands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            invocation.<RedisCallback<?>>getArgument(0).doInRedis(connection);
            throw new RedisSystemException("OOM command not allowed when used memory > 'maxmemory'", null);
        });

        assertThatThrownBy(() -> blocklistStore.replace(7, domains(3))).isInstanceOf(RedisSystemException.class);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(redisTemplate, times(2)).delete(LOADING_KEY);
    }

    @Test
    void aLoadThatRedisHoldsOnlyPartOfIsRefusedAndDropped() {
        when(connection.setCommands()).thenReturn(setCommands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(pipelineReturning(List.of(10_000L, true, 5_000L, true)));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(LOADING_KEY, LIVE_KEY)), eq("15000")))
                .thenThrow(new RedisSystemException("incomplete blocklist load: 4999 of 15000 entries", null));

        assertThatThrownBy(() -> blocklistStore.replace(7, domains(15_000))).isInstanceOf(RedisSystemException.class);

        verify(redisTemplate, times(2)).delete(LOADING_KEY);
    }

    @Test
    void anEmptySetNeverReplacesTheLiveSet() {
        assertThatThrownBy(() -> blocklistStore.replace(7, Set.of())).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void matchingSourcesMapsTheSmismemberAnswersBackToSourceIds() {
        final var keysBySource = new LinkedHashMap<Long, List<String>>();
        keysBySource.put(1L, List.of("a.evil.example", "evil.example"));
        keysBySource.put(2L, List.of("a.evil.example", "evil.example"));
        keysBySource.put(3L, List.of());
        keysBySource.put(4L, List.of("github.com/x/y/raw/evil.exe?dl=1", "github.com/x/y/raw/evil.exe"));
        when(connection.setCommands()).thenReturn(setCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenAnswer(pipelineReturning(List.of(List.of(false, true), List.of(false, false), List.of(false, true))));

        assertThat(blocklistStore.matchingSources(keysBySource)).containsExactlyInAnyOrder(1L, 4L);

        final InOrder order = inOrder(setCommands);
        order.verify(setCommands).sMIsMember(bytes("blocklist:source:1"), bytes("a.evil.example"), bytes("evil.example"));
        order.verify(setCommands).sMIsMember(bytes("blocklist:source:2"), bytes("a.evil.example"), bytes("evil.example"));
        order.verify(setCommands).sMIsMember(bytes("blocklist:source:4"),
                bytes("github.com/x/y/raw/evil.exe?dl=1"), bytes("github.com/x/y/raw/evil.exe"));
        verify(setCommands, never()).sMIsMember(eq(bytes("blocklist:source:3")), any(byte[][].class));
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    void matchingSourcesIsEmptyWhenRedisIsDown() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(blocklistStore.matchingSources(Map.of(1L, List.of("evil.example")))).isEmpty();
    }

    @Test
    void matchingSourcesWithNothingToLookUpNeverCallsRedis() {
        assertThat(blocklistStore.matchingSources(Map.of())).isEmpty();
        assertThat(blocklistStore.matchingSources(Map.of(1L, List.of()))).isEmpty();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void existsChecksTheLiveKey() {
        when(redisTemplate.hasKey(LIVE_KEY)).thenReturn(true);

        assertThat(blocklistStore.exists(7)).isTrue();
        assertThat(blocklistStore.exists(8)).isFalse();
    }

    @Test
    void removeDeletesTheLiveAndLoadingKeys() {
        blocklistStore.remove(7);

        verify(redisTemplate).delete(List.of(LIVE_KEY, LOADING_KEY));
    }

    @Test
    void removeWhileRedisIsDownDoesNotFail() {
        when(redisTemplate.delete(anyList())).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        blocklistStore.remove(7);

        verify(redisTemplate).delete(List.of(LIVE_KEY, LOADING_KEY));
    }

    @Test
    void memoryBytesReadsMemoryUsageOfTheLiveKey() {
        final ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        when(redisTemplate.execute(script.capture(), eq(List.of(LIVE_KEY)))).thenReturn(61_234_567L);

        assertThat(blocklistStore.memoryBytes(7)).isEqualTo(61_234_567L);
        assertThat(script.getValue().getScriptAsString()).contains("'MEMORY', 'USAGE', KEYS[1]");
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
    }

    @Test
    void memoryBytesIsUnknownWhenRedisIsDown() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(LIVE_KEY))))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(blocklistStore.memoryBytes(7)).isNull();
    }

    @Test
    void memoryReadsUsedAndMaxMemoryFromInfo() {
        final var info = new Properties();
        info.setProperty("used_memory", "73400320");
        info.setProperty("maxmemory", "536870912");
        info.setProperty("used_memory_human", "70.00M");
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info("memory")).thenReturn(info);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> invocation.<RedisCallback<?>>getArgument(0).doInRedis(connection));

        assertThat(blocklistStore.memory()).isEqualTo(new BlocklistStore.RedisMemory(73_400_320L, 536_870_912L));
    }

    private Answer<List<Object>> pipelineReturning(final List<Object> results) {
        return invocation -> {
            invocation.<RedisCallback<?>>getArgument(0).doInRedis(connection);
            return results;
        };
    }

    private static Set<String> domains(final int count) {
        return IntStream.range(0, count).mapToObj(index -> "domain" + index + ".example").collect(Collectors.toSet());
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(final byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

}
