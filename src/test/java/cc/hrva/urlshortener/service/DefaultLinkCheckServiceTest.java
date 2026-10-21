package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.dto.ReputationResult;
import cc.hrva.urlshortener.dto.ReputationResult.Verdict;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.impl.DefaultLinkCheckService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLinkCheckServiceTest {

    private static final String HAGEZI_FAKE = "HaGeZi Fake";
    private static final String HAGEZI_DYNDNS = "HaGeZi DynDNS";
    private static final String PHISHING_ARMY = "Phishing Army";
    private static final String URLHAUS = "URLhaus";
    private static final String SUSPICIOUS_LINKS = "Suspicious links";
    private static final String YOUNG = "domain registered in the last 30 days";
    private static final String LURE = "login or verification lure";
    private static final String SCAM_URL = "https://njuskalo-placanje.com/prijava";
    private static final String SCAM_HOST = "njuskalo-placanje.com";
    private static final LinkVerdict CLEAN = new LinkVerdict(Outcome.CLEAN, List.of(), null, null);

    private final List<DomainRule> rules = new ArrayList<>();
    private final List<BlocklistSource> sources = new ArrayList<>();
    private final Map<Long, Set<String>> listed = new HashMap<>();

    @Mock
    private DomainRuleRepository domainRuleRepository;

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private DomainAgeService domainAgeService;

    @Mock
    private DomainReputationService domainReputationService;

    @Mock
    private SuspiciousUrlService suspiciousUrlService;

    @Captor
    private ArgumentCaptor<Map<Long, List<String>>> keysBySource;

    private DefaultLinkCheckService linkCheckService;

    @BeforeEach
    void setUp() {
        lenient().when(domainRuleRepository.findByDomainIn(anyCollection())).thenAnswer(invocation -> {
            final Collection<String> domains = invocation.getArgument(0);
            return rules.stream().filter(rule -> domains.contains(rule.getDomain())).toList();
        });
        lenient().when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(sources);
        lenient().when(blocklistStore.matchingSources(anyMap())).thenAnswer(invocation -> matchingSources(invocation.getArgument(0)));
        lenient().when(domainReputationService.lookup(anyString())).thenReturn(ReputationResult.none());
        linkCheckService = new DefaultLinkCheckService(
                domainRuleRepository, blocklistSourceRepository, blocklistStore, domainAgeService, domainReputationService, suspiciousUrlService);
    }

    @Test
    void anAllowRuleOnTheParentWinsOverABlockSourceListingTheHost() {
        rule("example.com", DomainRuleType.ALLOW);
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "a.example.com");

        assertThat(verdictOf("https://a.example.com/page"))
                .isEqualTo(new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain example.com", "on list HaGeZi Fake"), null, null));
    }

    @Test
    void aBlockRuleBlocksTheDomainAndItsSubdomains() {
        rule("jofogas.cards", DomainRuleType.BLOCK);

        assertThat(verdictOf("https://www.jofogas.cards/termek/123"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("blocked domain jofogas.cards"), BlockReason.DOMAIN_RULE, "jofogas.cards"));
    }

    @Test
    void aHostOnABlockSourceIsBlocked() {
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");

        assertThat(verdictOf("https://fake-shop.com/sale"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("on list HaGeZi Fake"), BlockReason.BLOCKLIST, HAGEZI_FAKE));
    }

    @Test
    void aHostOnlyOnAFlagSourceIsFlagged() {
        source(2, HAGEZI_DYNDNS, SourceKind.DOMAINS, BlocklistAction.FLAG, "dynv6.net");

        assertThat(verdictOf("https://myhome.dynv6.net/"))
                .isEqualTo(new LinkVerdict(Outcome.FLAG, List.of("on list HaGeZi DynDNS"), null, null));
    }

    @Test
    void aYoungDomainImitatingABrandIsBlockedForScamSignals() {
        young(SCAM_HOST);
        heuristics(SCAM_URL, "imitates njuskalo");

        assertThat(verdictOf(SCAM_URL)).isEqualTo(new LinkVerdict(
                Outcome.BLOCK, List.of(YOUNG, "imitates njuskalo"), BlockReason.SCAM_SIGNALS, YOUNG + ", imitates njuskalo"));
    }

    @Test
    void aYoungDomainWithALoginLureIsBlockedAndTheDetailNamesOnlyTheScamSignals() {
        final var url = "https://secure-banka.top/prijava";
        source(2, HAGEZI_DYNDNS, SourceKind.DOMAINS, BlocklistAction.FLAG, "secure-banka.top");
        young("secure-banka.top");
        heuristics(url, LURE, "high-risk domain ending .top");

        assertThat(verdictOf(url)).isEqualTo(new LinkVerdict(
                Outcome.BLOCK,
                List.of("on list HaGeZi DynDNS", YOUNG, LURE, "high-risk domain ending .top"),
                BlockReason.SCAM_SIGNALS,
                YOUNG + ", " + LURE));
    }

    @Test
    void aBrandImitationWithALoginLureOnAnOldDomainIsOnlyFlagged() {
        final var url = "https://jofogas-belepes.com/login";
        heuristics(url, "imitates jofogas", LURE);

        assertThat(verdictOf(url)).isEqualTo(new LinkVerdict(Outcome.FLAG, List.of("imitates jofogas", LURE), null, null));
    }

    @Test
    void outlookWithALoginHintIsNotBlocked() {
        final var url = "https://outlook.office365.com/owa/?login_hint=x";
        heuristics(url, "imitates outlook", LURE);

        final var verdict = verdictOf(url);

        assertThat(verdict.outcome()).isNotEqualTo(Outcome.BLOCK).isEqualTo(Outcome.FLAG);
        assertThat(verdict.blockReason()).isNull();
    }

    @Test
    void aYoungDomainAloneIsFlagged() {
        young("brand-new-shop.com");

        assertThat(verdictOf("https://brand-new-shop.com/")).isEqualTo(new LinkVerdict(Outcome.FLAG, List.of(YOUNG), null, null));
    }

    @Test
    void aSingleHeuristicIsFlagged() {
        final var url = "https://cheap-deals.life/";
        heuristics(url, "high-risk domain ending .life");

        assertThat(verdictOf(url)).isEqualTo(new LinkVerdict(Outcome.FLAG, List.of("high-risk domain ending .life"), null, null));
    }

    @Test
    void nothingMatchingIsClean() {
        rule("other.com", DomainRuleType.BLOCK);
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");

        assertThat(verdictOf("https://example.com/article")).isEqualTo(CLEAN);
    }

    @Test
    void aRedisFailureWithNoOtherSignalIsClean() {
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");
        when(blocklistStore.matchingSources(anyMap())).thenReturn(Set.of());

        assertThat(verdictOf("https://fake-shop.com/sale")).isEqualTo(CLEAN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not a url", "http://", "https://exa mple.com/x"})
    void aMalformedUrlIsCleanWithoutAnyLookup(final String url) {
        assertThat(linkCheckService.check(url)).isEqualTo(CLEAN);
        assertThat(linkCheckService.explain(url)).isEqualTo(CLEAN);
        verifyNoInteractions(
                domainRuleRepository, blocklistSourceRepository, blocklistStore, domainAgeService, domainReputationService, suspiciousUrlService);
    }

    @Test
    void theExactLinkOnALinksBlockSourceIsBlockedAndAnotherFileIsNot() {
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK, "github.com/x/y/raw/evil.exe");

        assertThat(verdictOf("https://github.com/x/y/raw/evil.exe"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("on list URLhaus"), BlockReason.BLOCKLIST, URLHAUS));
        assertThat(verdictOf("https://github.com/x/y/raw/other.exe")).isEqualTo(CLEAN);
    }

    @Test
    void theListedLinkWithAnExtraQueryStringIsStillBlocked() {
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK, "github.com/x/y/raw/evil.exe");

        assertThat(verdictOf("https://github.com/x/y/raw/evil.exe?download=1"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("on list URLhaus"), BlockReason.BLOCKLIST, URLHAUS));
    }

    @Test
    void aSpamhausPhishingAnswerBlocks() {
        reputation("login-paypa1.com", Verdict.BLOCK, "phishing");

        assertThat(verdictOf("https://login-paypa1.com/"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("Spamhaus DBL: phishing"), BlockReason.BLOCKLIST, "Spamhaus DBL: phishing"));
    }

    @Test
    void aSpamhausAbusedLegitimateDomainAnswerFlags() {
        reputation("sites.example.com", Verdict.FLAG, "abused legitimate domain");

        assertThat(verdictOf("https://sites.example.com/page"))
                .isEqualTo(new LinkVerdict(Outcome.FLAG, List.of("Spamhaus DBL: abused legitimate domain"), null, null));
    }

    @Test
    void oneRedisCallCarriesTheDomainCandidatesAndTheLinkKeysOfFlagAndBlockSources() {
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK);
        source(2, HAGEZI_DYNDNS, SourceKind.DOMAINS, BlocklistAction.FLAG);
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK);
        source(4, SUSPICIOUS_LINKS, SourceKind.LINKS, BlocklistAction.FLAG);

        linkCheckService.check("https://cdn.evil.example.com/a?b=1");

        verify(blocklistStore, times(1)).matchingSources(keysBySource.capture());
        final var domains = List.of("cdn.evil.example.com", "evil.example.com", "example.com");
        final var links = List.of("cdn.evil.example.com/a?b=1", "cdn.evil.example.com/a");
        assertThat(keysBySource.getValue()).containsOnly(entry(1L, domains), entry(2L, domains), entry(3L, links), entry(4L, links));
    }

    @Test
    void aBlockRuleOnTheSubdomainBeatsAnAllowRuleOnTheParent() {
        rule("example.com", DomainRuleType.ALLOW);
        rule("evil.example.com", DomainRuleType.BLOCK);

        assertThat(verdictOf("https://evil.example.com/")).isEqualTo(new LinkVerdict(
                Outcome.BLOCK,
                List.of("blocked domain evil.example.com", "allowed domain example.com"),
                BlockReason.DOMAIN_RULE,
                "evil.example.com"));
    }

    @Test
    void anAllowRuleOnTheSubdomainBeatsABlockRuleOnTheParent() {
        rule("example.com", DomainRuleType.BLOCK);
        rule("a.example.com", DomainRuleType.ALLOW);

        assertThat(verdictOf("https://x.a.example.com/")).isEqualTo(new LinkVerdict(
                Outcome.ALLOWED, List.of("allowed domain a.example.com", "blocked domain example.com"), null, null));
    }

    @Test
    void anExactLinkOnALinksBlockSourceBeatsAnAllowRuleOnItsDomain() {
        rule("github.com", DomainRuleType.ALLOW);
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "github.com");
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK, "github.com/x/y/raw/evil.exe");

        assertThat(verdictOf("https://github.com/x/y/raw/evil.exe")).isEqualTo(new LinkVerdict(
                Outcome.BLOCK, List.of("allowed domain github.com", "on list HaGeZi Fake", "on list URLhaus"), BlockReason.BLOCKLIST, URLHAUS));
        assertThat(linkCheckService.explain("https://github.com/x/y/raw/evil.exe").reasons())
                .containsExactly("allowed domain github.com", "on list HaGeZi Fake", "on list URLhaus");
    }

    @Test
    void checkSkipsTheSlowLookupsWhenAnExactLinkBlockBeatsAnAllowRule() {
        rule("github.com", DomainRuleType.ALLOW);
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK, "github.com/x/y/raw/evil.exe");

        assertThat(linkCheckService.check("https://github.com/x/y/raw/evil.exe").outcome()).isEqualTo(Outcome.BLOCK);
        verifyNoInteractions(domainAgeService, domainReputationService);
    }

    @Test
    void anExactLinkOnALinksFlagSourceStaysAllowedUnderAnAllowRule() {
        rule("github.com", DomainRuleType.ALLOW);
        source(4, SUSPICIOUS_LINKS, SourceKind.LINKS, BlocklistAction.FLAG, "github.com/x/y/raw/evil.exe");

        assertThat(verdictOf("https://github.com/x/y/raw/evil.exe")).isEqualTo(new LinkVerdict(
                Outcome.ALLOWED, List.of("allowed domain github.com", "on list Suspicious links"), null, null));
    }

    @Test
    void checkSkipsTheSlowLookupsOnceTheCheapSignalsAllow() {
        rule("example.com", DomainRuleType.ALLOW);
        heuristics("https://example.com/login", LURE);

        assertThat(linkCheckService.check("https://example.com/login"))
                .isEqualTo(new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain example.com", LURE), null, null));
        verifyNoInteractions(domainAgeService, domainReputationService);
    }

    @Test
    void checkSkipsTheSlowLookupsOnceTheCheapSignalsBlock() {
        rule("jofogas.cards", DomainRuleType.BLOCK);
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK, "github.com/x/y/raw/evil.exe");

        assertThat(linkCheckService.check("https://jofogas.cards/").outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(linkCheckService.check("https://fake-shop.com/").outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(linkCheckService.check("https://github.com/x/y/raw/evil.exe").outcome()).isEqualTo(Outcome.BLOCK);
        verifyNoInteractions(domainAgeService, domainReputationService);
    }

    @Test
    void checkSkipsTheDomainAgeOnceSpamhausBlocks() {
        reputation("login-paypa1.com", Verdict.BLOCK, "phishing");

        assertThat(linkCheckService.check("https://login-paypa1.com/").outcome()).isEqualTo(Outcome.BLOCK);
        verifyNoInteractions(domainAgeService);
    }

    @Test
    void checkAsksForTheDomainAgeWhenTheCheapSignalsOnlyFlag() {
        source(2, HAGEZI_DYNDNS, SourceKind.DOMAINS, BlocklistAction.FLAG, "dynv6.net");

        linkCheckService.check("https://myhome.dynv6.net/");

        verify(domainReputationService).lookup("myhome.dynv6.net");
        verify(domainAgeService).isYoung("myhome.dynv6.net");
    }

    @Test
    void explainRunsEveryLookupAndListsEveryMatchEvenWhenAllowed() {
        final var url = "https://shop.example.com/login";
        rule("example.com", DomainRuleType.ALLOW);
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "shop.example.com");
        source(2, HAGEZI_DYNDNS, SourceKind.DOMAINS, BlocklistAction.FLAG, "example.com");
        reputation("shop.example.com", Verdict.BLOCK, "phishing");
        young("shop.example.com");
        heuristics(url, LURE);

        assertThat(linkCheckService.explain(url)).isEqualTo(new LinkVerdict(
                Outcome.ALLOWED,
                List.of("allowed domain example.com", "on list HaGeZi Fake", "on list HaGeZi DynDNS", "Spamhaus DBL: phishing", YOUNG, LURE),
                null,
                null));
        assertThat(linkCheckService.check(url).outcome()).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void aDomainRuleTakesPrecedenceOverTheListsSpamhausAndScamSignals() {
        rule(SCAM_HOST, DomainRuleType.BLOCK);
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, SCAM_HOST);
        reputation(SCAM_HOST, Verdict.BLOCK, "phishing");
        young(SCAM_HOST);
        heuristics(SCAM_URL, "imitates njuskalo", LURE);

        final var verdict = verdictOf(SCAM_URL);

        assertThat(verdict.blockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
        assertThat(verdict.blockDetail()).isEqualTo(SCAM_HOST);
        assertThat(linkCheckService.explain(SCAM_URL)).isEqualTo(new LinkVerdict(
                Outcome.BLOCK,
                List.of("blocked domain " + SCAM_HOST, "on list HaGeZi Fake", "Spamhaus DBL: phishing", YOUNG, "imitates njuskalo", LURE),
                BlockReason.DOMAIN_RULE,
                SCAM_HOST));
    }

    @Test
    void aBlockSourceTakesPrecedenceOverSpamhausAndScamSignals() {
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, SCAM_HOST);
        reputation(SCAM_HOST, Verdict.BLOCK, "phishing");
        young(SCAM_HOST);
        heuristics(SCAM_URL, "imitates njuskalo");

        final var verdict = verdictOf(SCAM_URL);

        assertThat(verdict.blockReason()).isEqualTo(BlockReason.BLOCKLIST);
        assertThat(verdict.blockDetail()).isEqualTo(HAGEZI_FAKE);
        assertThat(linkCheckService.explain(SCAM_URL).reasons())
                .containsExactly("on list HaGeZi Fake", "Spamhaus DBL: phishing", YOUNG, "imitates njuskalo");
    }

    @Test
    void spamhausTakesPrecedenceOverScamSignals() {
        reputation(SCAM_HOST, Verdict.BLOCK, "malware");
        young(SCAM_HOST);
        heuristics(SCAM_URL, "imitates njuskalo");

        final var verdict = verdictOf(SCAM_URL);

        assertThat(verdict.blockReason()).isEqualTo(BlockReason.BLOCKLIST);
        assertThat(verdict.blockDetail()).isEqualTo("Spamhaus DBL: malware");
        assertThat(linkCheckService.explain(SCAM_URL).reasons()).containsExactly("Spamhaus DBL: malware", YOUNG, "imitates njuskalo");
    }

    @Test
    void theLowestIdNamesTheDetailWhenSeveralBlockSourcesMatch() {
        source(7, PHISHING_ARMY, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");
        source(4, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");

        assertThat(verdictOf("https://fake-shop.com/")).isEqualTo(new LinkVerdict(
                Outcome.BLOCK, List.of("on list HaGeZi Fake", "on list Phishing Army"), BlockReason.BLOCKLIST, HAGEZI_FAKE));
    }

    @ParameterizedTest
    @CsvSource({"http://1.2.3.4/x, 1.2.3.4/x", "http://[2001:db8::1]/x, [2001:db8::1]/x"})
    void anIpAddressSkipsTheDomainChecksButNotTheLinkListsOrHeuristics(final String url, final String linkKey) {
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "1.2.3.4", "2.3.4", "3.4", "2001:db8::1");
        source(4, SUSPICIOUS_LINKS, SourceKind.LINKS, BlocklistAction.FLAG, linkKey);
        heuristics(url, "IP address instead of a domain");

        assertThat(linkCheckService.explain(url))
                .isEqualTo(new LinkVerdict(Outcome.FLAG, List.of("on list Suspicious links", "IP address instead of a domain"), null, null));
        verify(blocklistStore).matchingSources(keysBySource.capture());
        assertThat(keysBySource.getValue()).containsOnly(entry(1L, List.of()), entry(4L, List.of(linkKey)));
        verifyNoInteractions(domainRuleRepository, domainAgeService, domainReputationService);
    }

    @Test
    void anIpAddressOnALinksBlockSourceIsBlocked() {
        source(3, URLHAUS, SourceKind.LINKS, BlocklistAction.BLOCK, "1.2.3.4/payload.sh");

        assertThat(verdictOf("http://1.2.3.4/payload.sh"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("on list URLhaus"), BlockReason.BLOCKLIST, URLHAUS));
    }

    @Test
    void aFailingRuleLookupIsSkippedAndTheListsStillDecide() {
        when(domainRuleRepository.findByDomainIn(anyCollection())).thenThrow(new DataAccessResourceFailureException("database down"));
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");

        assertThat(verdictOf("https://fake-shop.com/"))
                .isEqualTo(new LinkVerdict(Outcome.BLOCK, List.of("on list HaGeZi Fake"), BlockReason.BLOCKLIST, HAGEZI_FAKE));
    }

    @Test
    void failingSlowLookupsAreSkippedAndTheHeuristicsStillDecide() {
        final var url = "https://xn--njukalo-cxa.com/";
        when(domainAgeService.isYoung(anyString())).thenThrow(new IllegalStateException("rdap down"));
        when(domainReputationService.lookup(anyString())).thenThrow(new IllegalStateException("dns down"));
        heuristics(url, "punycode domain");

        assertThat(verdictOf(url)).isEqualTo(new LinkVerdict(Outcome.FLAG, List.of("punycode domain"), null, null));
    }

    @Test
    void everyDependencyFailingGivesClean() {
        rule("example.com", DomainRuleType.BLOCK);
        when(domainRuleRepository.findByDomainIn(anyCollection())).thenThrow(new DataAccessResourceFailureException("database down"));
        when(blocklistSourceRepository.findByEnabledTrue()).thenThrow(new DataAccessResourceFailureException("database down"));
        when(domainAgeService.isYoung(anyString())).thenThrow(new IllegalStateException("rdap down"));
        when(domainReputationService.lookup(anyString())).thenThrow(new IllegalStateException("dns down"));
        when(suspiciousUrlService.findReasons(anyString())).thenThrow(new IllegalStateException("heuristics broken"));

        assertThat(verdictOf("https://example.com/")).isEqualTo(CLEAN);
    }

    @Test
    void aFailingBlocklistStoreIsSkipped() {
        source(1, HAGEZI_FAKE, SourceKind.DOMAINS, BlocklistAction.BLOCK, "fake-shop.com");
        when(blocklistStore.matchingSources(anyMap())).thenThrow(new IllegalStateException("redis broken"));
        young("fake-shop.com");

        assertThat(verdictOf("https://fake-shop.com/")).isEqualTo(new LinkVerdict(Outcome.FLAG, List.of(YOUNG), null, null));
    }

    private LinkVerdict verdictOf(final String url) {
        final var checked = linkCheckService.check(url);
        final var explained = linkCheckService.explain(url);
        assertThat(explained.outcome()).isEqualTo(checked.outcome());
        assertThat(explained.blockReason()).isEqualTo(checked.blockReason());
        assertThat(explained.blockDetail()).isEqualTo(checked.blockDetail());
        return checked;
    }

    private void rule(final String domain, final DomainRuleType type) {
        rules.add(DomainRule.builder().domain(domain).type(type).build());
    }

    private void source(final long id, final String name, final SourceKind kind, final BlocklistAction action, final String... entries) {
        sources.add(BlocklistSource.builder().id(id).name(name).kind(kind).action(action).build());
        listed.put(id, Set.of(entries));
    }

    private void young(final String host) {
        when(domainAgeService.isYoung(host)).thenReturn(true);
    }

    private void reputation(final String host, final Verdict verdict, final String type) {
        when(domainReputationService.lookup(host)).thenReturn(new ReputationResult(verdict, type));
    }

    private void heuristics(final String url, final String... reasons) {
        when(suspiciousUrlService.findReasons(url)).thenReturn(List.of(reasons));
    }

    private Set<Long> matchingSources(final Map<Long, List<String>> keys) {
        return keys.entrySet().stream()
                .filter(lookup -> lookup.getValue().stream().anyMatch(listed.getOrDefault(lookup.getKey(), Set.of())::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

}
