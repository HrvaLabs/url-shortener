package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.model.Campaign;
import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.CampaignLinkId;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import cc.hrva.urlshortener.repository.CampaignLinkRepository;
import cc.hrva.urlshortener.repository.CampaignRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.ShapeHeld;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import cc.hrva.urlshortener.service.campaign.UrlShapes;
import cc.hrva.urlshortener.service.impl.DefaultCampaignService;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultCampaignServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final LocalDateTime HELD_UNTIL = NOW_LOCAL.plusDays(7);
    private static final Duration DAY = Duration.ofHours(24);
    private static final String SHAPE = "/wqamhcsom?r&t";
    private static final String PATH = "/wqamhcsom";
    private static final UrlShape URL_SHAPE = UrlShapes.of("https://a.com/wqamhcsom?r=1&t=2").orElseThrow();
    private static final String SHAPE_KEY = URL_SHAPE.key();
    private static final String COUNTED_KEY = "campaign:shape:" + SHAPE_KEY;

    @Mock
    private RedisBurstCounter redisBurstCounter;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignLinkRepository campaignLinkRepository;

    @Mock
    private ModerationService moderationService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final AppProperties appProperties = new AppProperties();
    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(appProperties);
    private final List<String> window = new ArrayList<>();
    private final Map<String, Campaign> rows = new LinkedHashMap<>();
    private final Map<CampaignLinkId, CampaignLink> campaignLinks = new LinkedHashMap<>();
    private final List<Long> holds = new ArrayList<>();
    private final AtomicInteger staleReads = new AtomicInteger();
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        campaignService = new DefaultCampaignService(redisBurstCounter, urlRepository, campaignRepository, campaignLinkRepository, moderationService,
                eventPublisher, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), settingsService);
        storing();
    }

    @Test
    void theThirdLinkOnTheThirdDomainHoldsAllThree(final CapturedOutput output) {
        counting();
        aliases(link(42L, "bbb"), link(41L, "aaa"), link(43L, "ccc"));

        campaignService.recordAnonymousLink(41L, "https://ll.omaehhm.com/wqamhcsom/?r=plfN8D&t=1793210826", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://qdbplibnsx.fvvxctw.com/wqamhcsom?r=zB3vpw&t=1793185791", Outcome.FLAG);
        verifyNoInteractions(eventPublisher, moderationService);
        assertThat(rows).isEmpty();
        campaignService.recordAnonymousLink(43L, "https://login.c.com/wqamhcsom?t=1&r=2", Outcome.CLEAN);

        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "41|omaehhm.com", NOW, DAY);
        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "42|fvvxctw.com", NOW, DAY);
        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "43|c.com", NOW, DAY);
        final var campaign = onlyRow();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.HOLDING);
        assertThat(campaign.getPath()).isEqualTo(PATH);
        assertThat(campaign.getQueryNames()).containsExactly("r", "t");
        assertThat(campaign).extracting(Campaign::getFirstSeenAt, Campaign::getLastSeenAt, Campaign::getAlertedAt, Campaign::getActiveUntil)
                .containsExactly(NOW_LOCAL, NOW_LOCAL, NOW_LOCAL, HELD_UNTIL);
        assertThat(campaignLinks.values()).extracting(link -> link.getId().urlId(), CampaignLink::getDomain)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(41L, "omaehhm.com"), org.assertj.core.groups.Tuple.tuple(42L, "fvvxctw.com"),
                        org.assertj.core.groups.Tuple.tuple(43L, "c.com"));
        assertThat(holds).containsExactly(41L, 42L, 43L);
        verify(moderationService, times(3)).holdForCampaign(anyLong(), eq(SHAPE));
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
        verify(eventPublisher).publishEvent(new ShapeHeld(campaign.getId(), SHAPE, PATH, 3, 3, DAY, List.of("ccc", "bbb", "aaa"), true, 7));
        assertThat(output).containsOnlyOnce("Campaign alert shape=" + SHAPE_KEY + " display=/wqamhcsom?r&t campaignId=" + campaign.getId()
                        + " status=HOLDING links=3 domains=3")
                .doesNotContain("omaehhm", "c.com", "plfN8D");
    }

    @Test
    void aFourthLinkOnTheSameShapeIsHeldWithoutASecondAlert() {
        counting();
        aliases(link(43L, "ccc"));
        crossWithThreeLinks();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(holds).containsExactly(41L, 42L, 43L, 44L);
        assertThat(campaignLinks).hasSize(4);
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void theNewestThreeLinksAreTheExamples() {
        window.addAll(List.of("40|a.com", "41|b.com", "42|c.com", "43|d.com"));
        counting();
        aliases(link(44L, "eee"), link(42L, "ccc"), link(43L, "ddd"));

        campaignService.recordAnonymousLink(44L, "https://e.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        @SuppressWarnings("unchecked") final ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(urlRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(44L, 43L, 42L);
        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 5, 5, DAY, List.of("eee", "ddd", "ccc"), true, 7));
        assertThat(holds).containsExactly(40L, 41L, 42L, 43L, 44L);
    }

    @Test
    void indexPhpOnThreeSitesIsNotHeld() {
        campaignService.recordAnonymousLink(41L, "https://a.com/index.php?id=1", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/index.php?id=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(43L, "https://c.com/index.php?id=3", Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher, moderationService, campaignRepository, campaignLinkRepository);
    }

    @Test
    void oneDomainIsNeverACampaign() {
        counting();

        for (var urlId = 41L; urlId <= 45L; urlId++) {
            campaignService.recordAnonymousLink(urlId, "https://www.a.com/wqamhcsom?r=" + urlId + "&t=2", Outcome.CLEAN);
        }

        assertThat(window).hasSize(5).allMatch(member -> member.endsWith("|a.com"));
        verifyNoInteractions(eventPublisher, urlRepository, moderationService);
        assertThat(rows).isEmpty();
        assertThat(campaignLinks).isEmpty();
    }

    @Test
    void twoLinksOnTwoDomainsAreNotACampaign() {
        counting();

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verifyNoInteractions(eventPublisher, urlRepository, moderationService);
        assertThat(rows).isEmpty();
    }

    @Test
    void theSameLinkCountedTwiceIsOneLink() {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();

        campaignService.recordAnonymousLink(42L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verifyNoInteractions(eventPublisher, moderationService);
        assertThat(rows).isEmpty();
    }

    @Test
    void anAllowedDomainIsNeverCountedOrHeld() {
        rows.put(SHAPE_KEY, row(CampaignStatus.HOLDING, HELD_UNTIL));

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.ALLOWED);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher, moderationService, campaignRepository, campaignLinkRepository);
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"CLEAN", "FLAG"})
    void cleanAndFlaggedLinksAreCounted(final Outcome outcome) {
        counting();

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", outcome);

        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "41|a.com", NOW, DAY);
    }

    @Test
    void holdOffMakesAnAlertOnlyRowAndHoldsNothing() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CAMPAIGN_HOLD);
        counting();
        aliases(link(43L, "ccc"));

        crossWithThreeLinks();
        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        final var campaign = onlyRow();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ALERT_ONLY);
        assertThat(campaign.getActiveUntil()).isEqualTo(HELD_UNTIL);
        assertThat(campaignLinks).hasSize(4);
        verifyNoInteractions(moderationService);
        verify(eventPublisher).publishEvent(new ShapeHeld(campaign.getId(), SHAPE, PATH, 3, 3, DAY, List.of("ccc"), false, 7));
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isEmpty();
    }

    @Test
    void aShapeWithoutAQueryIsAlertOnlyEvenWithHoldOn() {
        final var bare = UrlShapes.of("https://a.com/login").orElseThrow();
        when(redisBurstCounter.recordUnderKey(eq("campaign:shape:" + bare.key()), anyString(), eq(NOW), eq(DAY))).thenAnswer(invocation -> {
            window.add(invocation.getArgument(1));
            return List.copyOf(window);
        });

        campaignService.recordAnonymousLink(41L, "https://a.com/login", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/login", Outcome.CLEAN);
        campaignService.recordAnonymousLink(43L, "https://c.com/login", Outcome.CLEAN);

        assertThat(rows.get(bare.key()).getStatus()).isEqualTo(CampaignStatus.ALERT_ONLY);
        assertThat(rows.get(bare.key()).getQueryNames()).isEmpty();
        verifyNoInteractions(moderationService);
        verify(eventPublisher).publishEvent(new ShapeHeld(rows.get(bare.key()).getId(), "/login", "/login", 3, 3, DAY, List.of(), false, 7));
        assertThat(campaignService.holdingCampaignId(bare)).isEmpty();
    }

    @Test
    void aMatchWithinTheHoldIsHeldAndExtendsIt() {
        final var campaign = row(CampaignStatus.HOLDING, NOW_LOCAL.plusDays(2));
        campaign.setLastSeenAt(NOW_LOCAL.minusDays(5));
        rows.put(SHAPE_KEY, campaign);
        window.add("40|z.com");
        counting();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(holds).containsExactly(44L);
        assertThat(campaignLinks.keySet()).containsExactly(new CampaignLinkId(campaign.getId(), 44L));
        assertThat(campaign.getActiveUntil()).isEqualTo(HELD_UNTIL);
        assertThat(campaign.getLastSeenAt()).isEqualTo(NOW_LOCAL);
        assertThat(campaign.getAlertedAt()).isEqualTo(NOW_LOCAL.minusDays(10));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aMatchOnAnAlertOnlyRowWhileTheHoldIsOffIsAddedAndExtendsWithoutAHold() {
        final var campaign = row(CampaignStatus.ALERT_ONLY, NOW_LOCAL.plusDays(2));
        rows.put(SHAPE_KEY, campaign);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CAMPAIGN_HOLD);
        counting();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(campaignLinks).hasSize(1);
        assertThat(campaign.getActiveUntil()).isEqualTo(HELD_UNTIL);
        verifyNoInteractions(eventPublisher, moderationService);
    }

    @Test
    void holdOffDuringAHoldAddsAndExtendsButHoldsNothingNew() {
        final var campaign = row(CampaignStatus.HOLDING, NOW_LOCAL.plusDays(2));
        rows.put(SHAPE_KEY, campaign);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CAMPAIGN_HOLD);
        counting();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.HOLDING);
        assertThat(campaignLinks).hasSize(1);
        assertThat(campaign.getActiveUntil()).isEqualTo(HELD_UNTIL);
        verifyNoInteractions(eventPublisher, moderationService);
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isEmpty();
    }

    @Test
    void afterTheHoldEndsANewCrossingAlertsAgainOnTheSameRow() {
        final var campaign = row(CampaignStatus.HOLDING, NOW_LOCAL.minusSeconds(1));
        rows.put(SHAPE_KEY, campaign);
        counting();
        aliases(link(53L, "ccc"));

        campaignService.recordAnonymousLink(51L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(52L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        verifyNoInteractions(eventPublisher, moderationService);
        assertThat(campaignLinks).isEmpty();
        campaignService.recordAnonymousLink(53L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        assertThat(onlyRow()).isSameAs(campaign);
        assertThat(campaign).extracting(Campaign::getStatus, Campaign::getFirstSeenAt, Campaign::getLastSeenAt, Campaign::getAlertedAt,
                Campaign::getActiveUntil).containsExactly(CampaignStatus.HOLDING, NOW_LOCAL.minusDays(10), NOW_LOCAL, NOW_LOCAL, HELD_UNTIL);
        assertThat(holds).containsExactly(51L, 52L, 53L);
        verify(eventPublisher).publishEvent(new ShapeHeld(campaign.getId(), SHAPE, PATH, 3, 3, DAY, List.of("ccc"), true, 7));
    }

    @Test
    void anAllowedShapeIsCountedButNeverHeldOrAlerted() {
        final var campaign = row(CampaignStatus.ALLOWED, NOW_LOCAL.minusDays(1));
        campaign.setLastSeenAt(NOW_LOCAL.minusDays(4));
        rows.put(SHAPE_KEY, campaign);
        counting();

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        assertThat(window).hasSize(3);
        assertThat(campaignLinks).hasSize(3);
        assertThat(campaign).extracting(Campaign::getStatus, Campaign::getLastSeenAt, Campaign::getActiveUntil)
                .containsExactly(CampaignStatus.ALLOWED, NOW_LOCAL, NOW_LOCAL.minusDays(1));
        verifyNoInteractions(eventPublisher, moderationService);
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isEmpty();
    }

    @Test
    void twoLinksCrossingTheThresholdTogetherMakeOneCampaignAndOneAlert() {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        aliases(link(43L, "ccc"));
        staleReads.set(2);

        campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        assertThat(rows).hasSize(1);
        verify(campaignRepository, times(2)).saveAndFlush(any(Campaign.class));
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
        assertThat(holds).containsExactly(41L, 42L, 43L, 44L);
        assertThat(campaignLinks).hasSize(4);
    }

    @Test
    void aLinkAlreadyInTheCampaignIsNotAddedTwice() {
        final var campaign = row(CampaignStatus.HOLDING, NOW_LOCAL.plusDays(2));
        rows.put(SHAPE_KEY, campaign);
        campaignLinks.put(new CampaignLinkId(campaign.getId(), 44L), CampaignLink.builder().id(new CampaignLinkId(campaign.getId(), 44L)).build());
        counting();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        verify(campaignLinkRepository, never()).saveAndFlush(any());
        assertThat(campaignLinks).hasSize(1);
        assertThat(holds).containsExactly(44L);
    }

    @Test
    void aWindowLinkDeletedMeanwhileIsSkipped(final CapturedOutput output) {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        aliases(link(43L, "ccc"));
        when(campaignLinkRepository.saveAndFlush(org.mockito.ArgumentMatchers.argThat(link -> link != null && link.getId().urlId() == 41L)))
                .thenThrow(new DataIntegrityViolationException("fk_campaign_link_url"));

        campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        assertThat(campaignLinks.keySet()).extracting(CampaignLinkId::urlId).containsExactly(42L, 43L);
        assertThat(holds).containsExactly(42L, 43L);
        verify(eventPublisher, times(1)).publishEvent(any(ShapeHeld.class));
        assertThat(output).containsOnlyOnce("Campaign link skipped campaignId=" + onlyRow().getId() + " urlId=41");
    }

    @Test
    void aWindowLinkThatFailsToBeAddedStillLeavesTheAlert(final CapturedOutput output) {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        aliases(link(43L, "ccc"));
        when(campaignLinkRepository.saveAndFlush(org.mockito.ArgumentMatchers.argThat(link -> link != null && link.getId().urlId() == 42L)))
                .thenThrow(new DataAccessResourceFailureException("Connection reset db.internal"));

        campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        assertThat(campaignLinks.keySet()).extracting(CampaignLinkId::urlId).containsExactly(41L, 43L);
        assertThat(holds).containsExactly(41L, 43L);
        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 3, 3, DAY, List.of("ccc"), true, 7));
        assertThat(output).containsOnlyOnce("Campaign link failed campaignId=" + onlyRow().getId()
                        + " urlId=42 exception=org.springframework.dao.DataAccessResourceFailureException")
                .doesNotContain("Campaign check skipped", "db.internal");
    }

    @Test
    void turningTheHoldOnMidCampaignStartsHoldingAtTheNextMatch(final CapturedOutput output) {
        final var campaign = row(CampaignStatus.ALERT_ONLY, NOW_LOCAL.plusDays(2));
        rows.put(SHAPE_KEY, campaign);
        campaignLinks.put(new CampaignLinkId(campaign.getId(), 41L), CampaignLink.builder().id(new CampaignLinkId(campaign.getId(), 41L)).build());
        counting();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.HOLDING);
        assertThat(holds).containsExactly(44L);
        assertThat(campaign.getActiveUntil()).isEqualTo(HELD_UNTIL);
        verifyNoInteractions(eventPublisher);
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).contains(campaign.getId());
        assertThat(output).containsOnlyOnce("Campaign started holding campaignId=" + campaign.getId());
    }

    @Test
    void anAlertOnlyRowStaysAlertOnlyWhileTheHoldIsOff() {
        final var campaign = row(CampaignStatus.ALERT_ONLY, NOW_LOCAL.plusDays(2));
        rows.put(SHAPE_KEY, campaign);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CAMPAIGN_HOLD);
        counting();

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ALERT_ONLY);
        verify(campaignRepository, never()).startHolding(anyLong());
        verifyNoInteractions(moderationService);
    }

    @Test
    void aThinShapesAlertOnlyRowNeverStartsHolding() {
        final var bare = UrlShapes.of("https://a.com/login").orElseThrow();
        final var campaign = row(CampaignStatus.ALERT_ONLY, NOW_LOCAL.plusDays(2));
        campaign.setShapeKey(bare.key());
        rows.put(bare.key(), campaign);
        when(redisBurstCounter.recordUnderKey(eq("campaign:shape:" + bare.key()), anyString(), eq(NOW), eq(DAY))).thenReturn(List.of("44|d.com"));

        campaignService.recordAnonymousLink(44L, "https://d.com/login", Outcome.CLEAN);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ALERT_ONLY);
        verify(campaignRepository, never()).startHolding(anyLong());
        verifyNoInteractions(moderationService);
    }

    @Test
    void aRowAnotherRunStartedHoldingIsHeldToo() {
        final var campaign = row(CampaignStatus.ALERT_ONLY, NOW_LOCAL.plusDays(2));
        rows.put(SHAPE_KEY, campaign);
        counting();
        org.mockito.Mockito.doReturn(0).when(campaignRepository).startHolding(campaign.getId());
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(row(CampaignStatus.HOLDING, NOW_LOCAL.plusDays(2))));

        campaignService.recordAnonymousLink(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        assertThat(holds).containsExactly(44L);
    }

    @Test
    void sameShapeAlertOffCountsNothingAndHoldsNothing() {
        rows.put(SHAPE_KEY, row(CampaignStatus.HOLDING, HELD_UNTIL));
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.SAME_SHAPE_ALERT);

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher, moderationService, campaignRepository, campaignLinkRepository);
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isEmpty();
    }

    @Test
    void theHoldDaysComeFromSettings() {
        SettingsServiceFixtures.change(settingsService, SettingKey.CAMPAIGN_HOLD_DAYS, 2);
        counting();
        aliases(link(43L, "ccc"));

        crossWithThreeLinks();

        assertThat(onlyRow().getActiveUntil()).isEqualTo(NOW_LOCAL.plusDays(2));
        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 3, 3, DAY, List.of("ccc"), true, 2));
    }

    @Test
    void theThresholdsAndSwitchComeFromSettings() {
        SettingsServiceFixtures.change(settingsService, SettingKey.CAMPAIGN_LINKS, 4);
        SettingsServiceFixtures.change(settingsService, SettingKey.CAMPAIGN_DOMAINS, 2);
        SettingsServiceFixtures.change(settingsService, SettingKey.CAMPAIGN_WINDOW_HOURS, 6);
        when(redisBurstCounter.recordUnderKey(eq(COUNTED_KEY), anyString(), eq(NOW), eq(Duration.ofHours(6)))).thenAnswer(invocation -> {
            window.add(invocation.getArgument(1));
            return List.copyOf(window);
        });
        aliases(link(41L, "aaa"), link(42L, "bbb"), link(43L, "ccc"), link(44L, "ddd"));

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(43L, "https://a.com/wqamhcsom?r=3&t=2", Outcome.CLEAN);
        verifyNoInteractions(eventPublisher);
        campaignService.recordAnonymousLink(44L, "https://b.com/wqamhcsom?r=4&t=2", Outcome.CLEAN);

        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 4, 2, Duration.ofHours(6), List.of("ddd", "ccc", "bbb"),
                true, 7));

        SettingsServiceFixtures.turnOff(settingsService, SettingKey.SAME_SHAPE_ALERT);
        campaignService.recordAnonymousLink(45L, "https://c.com/wqamhcsom?r=5&t=2", Outcome.CLEAN);

        verify(redisBurstCounter, times(4)).recordUnderKey(anyString(), anyString(), any(), any());
        assertThat(holds).containsExactly(41L, 42L, 43L, 44L);
    }

    @Test
    void theThresholdsComeFromConfiguration() {
        appProperties.getCampaign().setLinks(4);
        appProperties.getCampaign().setDomains(2);
        appProperties.getCampaign().setWindow(Duration.ofHours(6));
        appProperties.getModeration().setCampaignHoldDays(3);
        when(redisBurstCounter.recordUnderKey(eq(COUNTED_KEY), anyString(), eq(NOW), eq(Duration.ofHours(6)))).thenAnswer(invocation -> {
            window.add(invocation.getArgument(1));
            return List.copyOf(window);
        });
        aliases(link(41L, "aaa"), link(42L, "bbb"), link(43L, "ccc"), link(44L, "ddd"));

        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(43L, "https://a.com/wqamhcsom?r=3&t=2", Outcome.CLEAN);
        verifyNoInteractions(eventPublisher);
        campaignService.recordAnonymousLink(44L, "https://b.com/wqamhcsom?r=4&t=2", Outcome.CLEAN);

        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 4, 2, Duration.ofHours(6), List.of("ddd", "ccc", "bbb"),
                true, 3));
        assertThat(onlyRow().getActiveUntil()).isEqualTo(NOW_LOCAL.plusDays(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://203.0.113.7/wqamhcsom?r=1&t=2", "http://[2001:db8::1]/wqamhcsom?r=1&t=2", "http://127.1/wqamhcsom?r=1&t=2"})
    void anIpHostIsNeverCounted(final String longUrl) {
        campaignService.recordAnonymousLink(41L, longUrl, Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher, campaignRepository, moderationService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://", "http://%zz/wqamhcsom?r=1&t=2"})
    void anUnreadableDestinationIsNeverCounted(final String longUrl) {
        campaignService.recordAnonymousLink(41L, longUrl, Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher, campaignRepository, moderationService);
    }

    @Test
    void anExampleLinkDeletedMeanwhileIsLeftOut() {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        aliases(link(41L, "aaa"), link(43L, "ccc"));

        campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 3, 3, DAY, List.of("ccc", "aaa"), true, 7));
    }

    @Test
    void aFailingExampleLookupStillAlertsWithoutExamples(final CapturedOutput output) {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        when(urlRepository.findAllById(any())).thenThrow(new DataAccessResourceFailureException("Connection refused db.internal"));

        assertThatCode(() -> campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN)).doesNotThrowAnyException();

        verify(eventPublisher).publishEvent(new ShapeHeld(onlyRow().getId(), SHAPE, PATH, 3, 3, DAY, List.of(), true, 7));
        assertThat(output).containsOnlyOnce("Campaign examples skipped shape=" + SHAPE_KEY
                        + " exception=org.springframework.dao.DataAccessResourceFailureException")
                .containsOnlyOnce("Campaign alert shape=" + SHAPE_KEY)
                .doesNotContain("Campaign check skipped", "db.internal");
    }

    @Test
    void redisDownIsSkippedWithOneWarnLine(final CapturedOutput output) {
        when(redisBurstCounter.recordUnderKey(any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));

        assertThatCode(() -> campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN)).doesNotThrowAnyException();

        verifyNoInteractions(urlRepository, eventPublisher, campaignRepository, moderationService);
        assertThat(output).containsOnlyOnce("Campaign check skipped shape=" + SHAPE_KEY
                        + " exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("redis.internal", "wqamhcsom", "a.com");
    }

    @Test
    void theDatabaseDownIsSkippedWithOneWarnLine(final CapturedOutput output) {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        when(campaignRepository.findByShapeKey(SHAPE_KEY)).thenThrow(new DataAccessResourceFailureException("Connection refused db.internal"));

        assertThatCode(() -> campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN)).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher, moderationService);
        assertThat(output).containsOnlyOnce("Campaign check skipped shape=" + SHAPE_KEY
                        + " exception=org.springframework.dao.DataAccessResourceFailureException")
                .doesNotContain("db.internal");
    }

    @Test
    void theHoldingCampaignIsTheHoldingRowWhileItsHoldLasts() {
        final var campaign = row(CampaignStatus.HOLDING, NOW_LOCAL.plusSeconds(1));
        rows.put(SHAPE_KEY, campaign);

        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).contains(campaign.getId());

        campaign.setActiveUntil(NOW_LOCAL);
        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isEmpty();
        assertThat(campaignService.holdingCampaignId(UrlShapes.of("https://a.com/other?r=1").orElseThrow())).isEmpty();
    }

    @Test
    void aShapeWithoutAQueryIsNeverHeldAtCreate() {
        final var bare = UrlShapes.of("https://a.com/login").orElseThrow();
        rows.put(bare.key(), row(CampaignStatus.HOLDING, HELD_UNTIL));

        assertThat(campaignService.holdingCampaignId(bare)).isEmpty();
        verifyNoInteractions(campaignRepository);
    }

    @Test
    void holdOffHoldsNothingAtCreate() {
        rows.put(SHAPE_KEY, row(CampaignStatus.HOLDING, HELD_UNTIL));
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CAMPAIGN_HOLD);

        assertThat(campaignService.holdingCampaignId(URL_SHAPE)).isEmpty();
        verifyNoInteractions(campaignRepository);
    }

    @Test
    void addingAHeldLinkStoresItAndExtendsTheHold() {
        final var campaign = row(CampaignStatus.HOLDING, NOW_LOCAL.plusDays(1));
        campaign.setLastSeenAt(NOW_LOCAL.minusDays(1));
        rows.put(SHAPE_KEY, campaign);

        campaignService.addHeldLink(campaign.getId(), 77L, "omaehhm.com");

        final var stored = campaignLinks.get(new CampaignLinkId(campaign.getId(), 77L));
        assertThat(stored).extracting(CampaignLink::getDomain, CampaignLink::getCreatedAt).containsExactly("omaehhm.com", NOW_LOCAL);
        assertThat(campaign).extracting(Campaign::getLastSeenAt, Campaign::getActiveUntil).containsExactly(NOW_LOCAL, HELD_UNTIL);
        verifyNoInteractions(moderationService, eventPublisher);
    }

    @Test
    void addingAHeldLinkRunsInTheCallersTransaction() throws NoSuchMethodException {
        final var transactional = DefaultCampaignService.class.getMethod("addHeldLink", long.class, long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void theCheckRunsOnTheDefaultTaskExecutor() throws NoSuchMethodException {
        final var async = DefaultCampaignService.class.getMethod("recordAnonymousLink", long.class, String.class, Outcome.class)
                .getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEmpty();
    }

    private void crossWithThreeLinks() {
        campaignService.recordAnonymousLink(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        campaignService.recordAnonymousLink(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
    }

    private void counting() {
        when(redisBurstCounter.recordUnderKey(eq(COUNTED_KEY), anyString(), eq(NOW), eq(DAY))).thenAnswer(invocation -> {
            window.add(invocation.getArgument(1));
            return List.copyOf(window);
        });
    }

    private void aliases(final Url... links) {
        lenient().when(urlRepository.findAllById(any())).thenReturn(List.of(links));
    }

    private void storing() {
        final var nextId = new AtomicInteger(4);
        lenient().when(campaignRepository.findByShapeKey(anyString())).thenAnswer(invocation -> staleReads.getAndUpdate(left -> Math.max(0, left - 1)) > 0
                ? Optional.empty()
                : Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        lenient().when(campaignRepository.saveAndFlush(any(Campaign.class))).thenAnswer(invocation -> {
            final Campaign campaign = invocation.getArgument(0);
            if (rows.containsKey(campaign.getShapeKey())) {
                throw new DataIntegrityViolationException("ux_campaign_shape_key");
            }
            campaign.setId((long) nextId.getAndIncrement());
            rows.put(campaign.getShapeKey(), campaign);
            return campaign;
        });
        lenient().when(campaignRepository.reopen(anyLong(), any(), any(), any())).thenAnswer(invocation -> byId(invocation.getArgument(0))
                .filter(campaign -> !campaign.getActiveUntil().isAfter(invocation.getArgument(2)) && campaign.getStatus() != CampaignStatus.ALLOWED)
                .map(campaign -> {
                    campaign.setStatus(invocation.getArgument(1));
                    campaign.setLastSeenAt(invocation.getArgument(2));
                    campaign.setAlertedAt(invocation.getArgument(2));
                    campaign.setActiveUntil(invocation.getArgument(3));
                    return 1;
                }).orElse(0));
        lenient().when(campaignRepository.extendHold(anyLong(), any(), any())).thenAnswer(invocation -> byId(invocation.getArgument(0)).map(campaign -> {
            campaign.setLastSeenAt(invocation.getArgument(1));
            campaign.setActiveUntil(invocation.getArgument(2));
            return 1;
        }).orElse(0));
        lenient().when(campaignRepository.markSeen(anyLong(), any())).thenAnswer(invocation -> byId(invocation.getArgument(0)).map(campaign -> {
            campaign.setLastSeenAt(invocation.getArgument(1));
            return 1;
        }).orElse(0));
        lenient().when(campaignRepository.startHolding(anyLong())).thenAnswer(invocation -> byId(invocation.getArgument(0))
                .filter(campaign -> campaign.getStatus() == CampaignStatus.ALERT_ONLY)
                .map(campaign -> {
                    campaign.setStatus(CampaignStatus.HOLDING);
                    return 1;
                }).orElse(0));
        lenient().when(campaignLinkRepository.existsById(any())).thenAnswer(invocation -> campaignLinks.containsKey(invocation.<CampaignLinkId>getArgument(0)));
        lenient().when(campaignLinkRepository.saveAndFlush(any(CampaignLink.class))).thenAnswer(invocation -> {
            final CampaignLink link = invocation.getArgument(0);
            if (campaignLinks.putIfAbsent(link.getId(), link) != null) {
                throw new DataIntegrityViolationException("pk_campaign_link");
            }
            return link;
        });
        lenient().when(moderationService.holdForCampaign(anyLong(), anyString())).thenAnswer(invocation -> {
            holds.add(invocation.getArgument(0));
            return true;
        });
    }

    private Optional<Campaign> byId(final long id) {
        return rows.values().stream().filter(campaign -> Objects.equals(campaign.getId(), id)).findFirst();
    }

    private Campaign onlyRow() {
        assertThat(rows).hasSize(1);
        return rows.values().iterator().next();
    }

    private static Campaign row(final CampaignStatus status, final LocalDateTime activeUntil) {
        return Campaign.builder()
                .id(4L)
                .shapeKey(SHAPE_KEY)
                .path(PATH)
                .queryNames(List.of("r", "t"))
                .status(status)
                .firstSeenAt(NOW_LOCAL.minusDays(10))
                .lastSeenAt(NOW_LOCAL.minusDays(1))
                .activeUntil(activeUntil)
                .alertedAt(NOW_LOCAL.minusDays(10))
                .build();
    }

    private static Url link(final long id, final String alias) {
        return Url.builder().id(id).shortUrl(alias).build();
    }

}
