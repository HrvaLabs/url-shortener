package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.blocklist.HostListing.BrandSite;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostListsTest {

    private static final long SHORTENERS = 1L;
    private static final long ENDINGS = 2L;
    private static final long BRANDS = 3L;
    private static final long BLOCKED = 4L;
    private static final long LINKS = 5L;
    private static final long TRANCO = 6L;

    private final List<BlocklistSource> sources = new ArrayList<>();
    private final Map<Long, Set<String>> listed = new HashMap<>();

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Captor
    private ArgumentCaptor<Map<Long, List<String>>> keysBySource;

    private HostLists hostLists;

    @BeforeEach
    void setUp() {
        lenient().when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(sources);
        lenient().when(blocklistStore.matchingSources(anyMap())).thenAnswer(invocation -> matchingSources(invocation.getArgument(0)));
        lenient().when(blocklistStore.exists(anyLong())).thenAnswer(invocation -> listed.containsKey((long) invocation.getArgument(0)));
        hostLists = new HostLists(blocklistSourceRepository, blocklistStore);
    }

    @Test
    void aShortenerMatchesTheHostOrAParentDomain() {
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");

        assertThat(hostLists.lookup("sub.bit.ly").shortener()).isTrue();
        assertThat(hostLists.lookup("bit.ly").shortener()).isTrue();
        assertThat(hostLists.lookup("example.com").shortener()).isFalse();
    }

    @Test
    void anEndingMatchesTheLastLabel() {
        source(ENDINGS, SourceKind.ENDINGS, BlocklistAction.NOTE, "bet");

        assertThat(hostLists.lookup("shop.example.bet").riskyEnding()).isTrue();
        assertThat(hostLists.lookup("shop.example.com").riskyEnding()).isFalse();
    }

    @Test
    void aPunycodeEndingIsLookedUpInAscii() {
        source(ENDINGS, SourceKind.ENDINGS, BlocklistAction.NOTE, "xn--6frz82g");

        assertThat(hostLists.lookup("shop.example.xn--6frz82g").riskyEnding()).isTrue();
    }

    @Test
    void aBrandSiteMatchesTheRegistryDomain() {
        source(BRANDS, SourceKind.BRAND_SITES, BlocklistAction.TRUST, "njuskalo.hr");

        assertThat(hostLists.lookup("www.njuskalo.hr").brandSite()).isEqualTo(BrandSite.LISTED);
        verify(blocklistStore).matchingSources(keysBySource.capture());
        assertThat(keysBySource.getValue()).containsEntry(BRANDS, List.of("njuskalo.hr"));
    }

    @Test
    void anUnlistedBrandNamedDomainIsNotListed() {
        source(BRANDS, SourceKind.BRAND_SITES, BlocklistAction.TRUST, "njuskalo.hr");

        assertThat(hostLists.lookup("njuskalo-login.xyz").brandSite()).isEqualTo(BrandSite.NOT_LISTED);
    }

    @Test
    void aBrandListNeverLoadedIsUnknown() {
        sources.add(enabled(BRANDS, SourceKind.BRAND_SITES, BlocklistAction.TRUST));

        assertThat(hostLists.lookup("njuskalo.hr")).isEqualTo(HostListing.unknown());
    }

    @Test
    void aSwitchedOffBrandListIsUnknown() {
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");

        assertThat(hostLists.lookup("bit.ly")).isEqualTo(new HostListing(true, false, BrandSite.UNKNOWN));
    }

    @Test
    void aFailedLookupIsUnknownAndUnlisted() {
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");
        when(blocklistStore.matchingSources(anyMap())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(hostLists.lookup("bit.ly")).isEqualTo(HostListing.unknown());
    }

    @Test
    void aBrandSetLostFromRedisIsUnknown() {
        final var brandSites = enabled(BRANDS, SourceKind.BRAND_SITES, BlocklistAction.TRUST);
        brandSites.setDomainCount(150_000L);
        sources.add(brandSites);
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");

        assertThat(hostLists.lookup("www.njuskalo.hr").brandSite()).isEqualTo(BrandSite.UNKNOWN);
        verify(blocklistStore).exists(BRANDS);
    }

    @Test
    void blockingAndTopSitesListsAreNotAsked() {
        source(BLOCKED, SourceKind.DOMAINS, BlocklistAction.BLOCK, "bit.ly");
        source(LINKS, SourceKind.LINKS, BlocklistAction.BLOCK, "bit.ly");
        source(TRANCO, SourceKind.TOP_SITES, BlocklistAction.TRUST, "bit.ly");
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");

        hostLists.lookup("bit.ly");

        verify(blocklistStore).matchingSources(keysBySource.capture());
        assertThat(keysBySource.getValue()).containsOnlyKeys(SHORTENERS);
        verify(blocklistStore, never()).exists(TRANCO);
    }

    @Test
    void oneMatchingCallPerLookup() {
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");
        source(ENDINGS, SourceKind.ENDINGS, BlocklistAction.NOTE, "bet");
        source(BRANDS, SourceKind.BRAND_SITES, BlocklistAction.TRUST, "njuskalo.hr");

        hostLists.lookup("www.njuskalo.bet");

        verify(blocklistStore, times(1)).matchingSources(anyMap());
        verify(blocklistStore, times(1)).exists(BRANDS);
    }

    @Test
    void aHostWithCapitalsAndATrailingDotMatches() {
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");

        assertThat(hostLists.lookup("BIT.LY.").shortener()).isTrue();
    }

    @Test
    void anIpLiteralIsNeverAskedAbout() {
        source(SHORTENERS, SourceKind.SHORTENERS, BlocklistAction.NOTE, "bit.ly");

        assertThat(hostLists.lookup("142.250.180.14")).isEqualTo(new HostListing(false, false, BrandSite.NOT_LISTED));
        verifyNoInteractions(blocklistStore);
    }

    private BlocklistSource enabled(final long id, final SourceKind kind, final BlocklistAction action) {
        return BlocklistSource.builder().id(id).name("Source " + id).kind(kind).action(action).build();
    }

    private void source(final long id, final SourceKind kind, final BlocklistAction action, final String... entries) {
        sources.add(enabled(id, kind, action));
        listed.put(id, Set.of(entries));
    }

    private Set<Long> matchingSources(final Map<Long, List<String>> keys) {
        return keys.entrySet().stream()
                .filter(lookup -> lookup.getValue().stream().anyMatch(listed.getOrDefault(lookup.getKey(), Set.of())::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

}
