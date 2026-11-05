package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealSignInHostsTest {

    private static final long TRANCO = 10L;
    private static final long HAGEZI_FAKE = 2L;

    private final List<BlocklistSource> sources = new ArrayList<>();
    private final Map<Long, Set<String>> listed = new HashMap<>();

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Captor
    private ArgumentCaptor<Map<Long, List<String>>> keysBySource;

    private RealSignInHosts realSignInHosts;

    @BeforeEach
    void setUp() {
        lenient().when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(sources);
        lenient().when(blocklistStore.matchingSources(anyMap())).thenAnswer(invocation -> matchingSources(invocation.getArgument(0)));
        realSignInHosts = new RealSignInHosts(blocklistSourceRepository, blocklistStore);
    }

    @Test
    void accountsGoogleComIsRealWhenItIsListed() {
        trancoLists("google.com", "accounts.google.com", "login.microsoftonline.com");

        assertThat(realSignInHosts.isRealSignInHost("accounts.google.com")).isTrue();
        assertThat(realSignInHosts.isRealSignInHost("login.microsoftonline.com")).isTrue();
        verify(blocklistStore, times(2)).matchingSources(keysBySource.capture());
        assertThat(keysBySource.getAllValues().getFirst()).containsExactly(entry(TRANCO, List.of("accounts.google.com")));
    }

    @Test
    void aSubdomainOfAListedDomainIsNotReal() {
        trancoLists("sharepoint.com", "google.com", "accounts.google.com");

        assertThat(realSignInHosts.isRealSignInHost("contoso.sharepoint.com")).isFalse();
        assertThat(realSignInHosts.isRealSignInHost("script.google.com")).isFalse();
        assertThat(realSignInHosts.isRealSignInHost("login.accounts.google.com")).isFalse();
    }

    @Test
    void aHostIsLookedUpInItsListedForm() {
        trancoLists("accounts.google.com", "xn--bcher-kva.de");

        assertThat(realSignInHosts.isRealSignInHost("Accounts.Google.COM.")).isTrue();
        assertThat(realSignInHosts.isRealSignInHost("bücher.de")).isTrue();
    }

    @Test
    void aGithubIoPageIsNeverReal() {
        trancoLists("github.io", "foo.github.io", "login.foo.github.io");

        assertThat(realSignInHosts.isRealSignInHost("foo.github.io")).isFalse();
        assertThat(realSignInHosts.isRealSignInHost("login.foo.github.io")).isFalse();
        verifyNoInteractions(blocklistStore);
    }

    @Test
    void anUnknownDomainIsNotReal() {
        trancoLists("accounts.google.com");

        assertThat(realSignInHosts.isRealSignInHost("accounts.google.com.orvexa.bet")).isFalse();
        assertThat(realSignInHosts.isRealSignInHost("namora.mobile")).isFalse();
    }

    @Test
    void onlyATrustSourceMakesASignInPageReal() {
        trancoLists("accounts.google.com");
        source(HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "login.njuskaio.cam");
        source(3L, SourceKind.DOMAINS, BlocklistAction.FLAG, "login.bulletproof-hosting.com");

        assertThat(realSignInHosts.isRealSignInHost("login.njuskaio.cam")).isFalse();
        assertThat(realSignInHosts.isRealSignInHost("login.bulletproof-hosting.com")).isFalse();
        verify(blocklistStore, times(2)).matchingSources(keysBySource.capture());
        assertThat(keysBySource.getAllValues()).allSatisfy(lookups -> assertThat(lookups).containsOnlyKeys(TRANCO));
    }

    @Test
    void aRedisFailureCountsAsNotReal() {
        trancoLists("accounts.google.com");
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        final var overFailingRedis = new RealSignInHosts(blocklistSourceRepository, new BlocklistStore(redisTemplate));

        assertThat(overFailingRedis.isRealSignInHost("accounts.google.com")).isFalse();
    }

    @Test
    void aDatabaseFailureCountsAsNotReal() {
        when(blocklistSourceRepository.findByEnabledTrue()).thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThat(realSignInHosts.isRealSignInHost("accounts.google.com")).isFalse();
        verifyNoInteractions(blocklistStore);
    }

    @Test
    void nothingIsRealBeforeTheSourceLoads() {
        source(TRANCO, SourceKind.TOP_SITES, BlocklistAction.TRUST);

        assertThat(realSignInHosts.isRealSignInHost("accounts.google.com")).isFalse();
    }

    @Test
    void nothingIsRealWithoutATrustSource() {
        source(HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "accounts.google.com");

        assertThat(realSignInHosts.isRealSignInHost("accounts.google.com")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"142.250.180.14", "::1", "2001:db8::1"})
    void anIpIsNeverReal(final String host) {
        trancoLists("142.250.180.14", "::1", "2001:db8::1");

        assertThat(realSignInHosts.isRealSignInHost(host)).isFalse();
        verifyNoInteractions(blocklistStore);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingHostIsNeverReal(final String host) {
        trancoLists("accounts.google.com");

        assertThat(realSignInHosts.isRealSignInHost(host)).isFalse();
        verifyNoInteractions(blocklistStore);
    }

    private void trancoLists(final String... hosts) {
        source(TRANCO, SourceKind.TOP_SITES, BlocklistAction.TRUST, hosts);
    }

    private void source(final long id, final SourceKind kind, final BlocklistAction action, final String... entries) {
        sources.add(BlocklistSource.builder().id(id).name("Source " + id).kind(kind).action(action).build());
        listed.put(id, Set.of(entries));
    }

    private Set<Long> matchingSources(final Map<Long, List<String>> keys) {
        return keys.entrySet().stream()
                .filter(lookup -> lookup.getValue().stream().anyMatch(listed.getOrDefault(lookup.getKey(), Set.of())::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

}
