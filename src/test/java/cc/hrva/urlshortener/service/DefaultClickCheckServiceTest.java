package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.service.blocklist.BlocklistConfigurationChangedEvent;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.impl.DefaultClickCheckService;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultClickCheckServiceTest {

    private static final long URL_ID = 7L;
    private static final String PHISHING_LINK = "https://login.jofogas.cards/item/7?ref=sms";
    private static final List<String> PHISHING_DOMAINS = List.of("login.jofogas.cards", "jofogas.cards");
    private static final List<String> PHISHING_LINK_KEYS = List.of("login.jofogas.cards/item/7?ref=sms", "login.jofogas.cards/item/7");
    private static final BlocklistSource TIF = source(1L, "HaGeZi TIF medium", SourceKind.DOMAINS, BlocklistAction.BLOCK);
    private static final BlocklistSource OPENPHISH = source(2L, "OpenPhish", SourceKind.LINKS, BlocklistAction.BLOCK);
    private static final BlocklistSource HOSTERS = source(3L, "HaGeZi badware hosters", SourceKind.DOMAINS, BlocklistAction.FLAG);
    private static final BlocklistSource PHISHING_ARMY = source(5L, "Phishing Army", SourceKind.DOMAINS, BlocklistAction.BLOCK);

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private DomainRuleRepository domainRuleRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private ModerationService moderationService;

    @Captor
    private ArgumentCaptor<Map<Long, List<String>>> lookups;

    private ClickCheckService clickCheckService;

    @BeforeEach
    void setUp() {
        clickCheckService = new DefaultClickCheckService(blocklistSourceRepository, domainRuleRepository, blocklistStore, moderationService);
    }

    @Test
    void aDestinationOnABlockListBlocksTheLinkAndRefusesTheRedirect() {
        lists(TIF);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();

        verify(moderationService, times(1)).blockLink(URL_ID, BlockReason.BLOCKLIST, "HaGeZi TIF medium", "system", "click");
        verify(blocklistStore).matchingSources(lookups.capture());
        assertThat(lookups.getValue()).containsOnly(entry(1L, PHISHING_DOMAINS));
    }

    @Test
    void aDestinationOnlyOnAFlagListIsLetThroughAndTheFlagListIsNeverAsked() {
        lists(HOSTERS);
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isTrue();

        verifyNoInteractions(blocklistStore, moderationService);
    }

    @Test
    void anAllowRuleOnTheRegistrableDomainLetsTheVisitorThroughWithoutAskingRedis() {
        lists(TIF);
        allowed("jofogas.cards");
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isTrue();

        verifyNoInteractions(blocklistStore, moderationService);
    }

    @Test
    void anAllowRuleOnASiblingSubdomainDoesNotCoverTheHost() {
        lists(TIF);
        allowed("shop.jofogas.cards");
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();
    }

    @Test
    void anExactLinkBlockStillAppliesUnderAnAllowRule() {
        lists(TIF, OPENPHISH);
        allowed("jofogas.cards");
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(2L));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();

        verify(blocklistStore).matchingSources(lookups.capture());
        assertThat(lookups.getValue()).containsOnly(entry(2L, PHISHING_LINK_KEYS));
        verify(moderationService).blockLink(URL_ID, BlockReason.BLOCKLIST, "OpenPhish", "system", "click");
    }

    @Test
    void aRedisFailureLetsTheVisitorThrough() {
        lists(TIF);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of());
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isTrue();

        verifyNoInteractions(moderationService);
    }

    @Test
    void anUnexpectedLookupErrorLetsTheVisitorThrough() {
        lists(TIF);
        when(blocklistStore.matchingSources(any())).thenThrow(new IllegalStateException("pipeline closed"));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isTrue();

        verifyNoInteractions(moderationService);
    }

    @Test
    void aFailedBlockStillRefusesTheRedirectAndIsLoggedWithoutTheDestination(final CapturedOutput output) {
        lists(TIF);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
        when(moderationService.blockLink(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();

        assertThat(output).contains("urlId=7", "database down").doesNotContain("jofogas");
    }

    @Test
    void withoutAnEnabledBlockListRedisIsNeverAsked() {
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isTrue();

        verifyNoInteractions(blocklistStore, moderationService);
    }

    @Test
    void anExactLinkIsLookedUpInTheSamePipelinedCallAsTheDomains() {
        lists(TIF, HOSTERS, OPENPHISH);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(2L));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();

        verify(blocklistStore, times(1)).matchingSources(lookups.capture());
        assertThat(lookups.getValue()).containsOnly(entry(1L, PHISHING_DOMAINS), entry(2L, PHISHING_LINK_KEYS));
        verify(moderationService).blockLink(URL_ID, BlockReason.BLOCKLIST, "OpenPhish", "system", "click");
    }

    @Test
    void anIpLiteralSkipsTheDomainListsButStillChecksTheExactLinks() {
        lists(TIF, OPENPHISH);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of());
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, "http://203.0.113.7/login")).isTrue();

        verify(blocklistStore).matchingSources(lookups.capture());
        assertThat(lookups.getValue()).containsOnly(entry(2L, List.of("203.0.113.7/login")));
    }

    @Test
    void whenSeveralListsMatchTheLowestIdNamesTheBlock() {
        lists(PHISHING_ARMY, TIF);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(5L, 1L));
        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();

        verify(moderationService).blockLink(URL_ID, BlockReason.BLOCKLIST, "HaGeZi TIF medium", "system", "click");
    }

    @Test
    void beforeTheFirstLoadEveryVisitorIsLetThroughWithoutAskingRedis() {
        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isTrue();

        verifyNoInteractions(blocklistStore, moderationService);
    }

    @Test
    void aFailedReloadKeepsTheListsLoadedBefore() {
        when(blocklistSourceRepository.findByEnabledTrue())
                .thenReturn(List.of(TIF))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
        clickCheckService.reload();

        clickCheckService.reload();

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();
    }

    @Test
    void aPublishedConfigurationChangeReloadsTheLists() {
        lists(TIF);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
        try (final var context = listeningContext()) {
            context.publishEvent(new BlocklistConfigurationChangedEvent());
        }

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();
    }

    @Test
    void theListsAreLoadedWhenTheApplicationIsReady() {
        lists(TIF);
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
        try (final var context = listeningContext()) {
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        }

        assertThat(clickCheckService.mayRedirect(URL_ID, PHISHING_LINK)).isFalse();
    }

    @Test
    void theListsAreReloadedEveryFiveMinutes() throws NoSuchMethodException {
        final var scheduled = DefaultClickCheckService.class.getMethod("reload").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(Duration.ofMinutes(5).toMillis());
    }

    private AnnotationConfigApplicationContext listeningContext() {
        final var context = new AnnotationConfigApplicationContext();
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(DefaultClickCheckService.class, () -> (DefaultClickCheckService) clickCheckService);
        context.refresh();
        return context;
    }

    private void lists(final BlocklistSource... sources) {
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(sources));
    }

    private void allowed(final String... domains) {
        when(domainRuleRepository.findByType(DomainRuleType.ALLOW)).thenReturn(Arrays.stream(domains)
                .map(domain -> DomainRule.builder().domain(domain).type(DomainRuleType.ALLOW).build())
                .toList());
    }

    private static BlocklistSource source(final long id, final String name, final SourceKind kind, final BlocklistAction action) {
        return BlocklistSource.builder().id(id).name(name).kind(kind).action(action).build();
    }

}
