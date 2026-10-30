package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.DestinationProber;
import cc.hrva.urlshortener.client.ProbeOutcome;
import cc.hrva.urlshortener.client.ProbeResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultDeadLinkNotificationService;
import cc.hrva.urlshortener.service.impl.DefaultDestinationCheckService;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.HostPacer;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.notification.NetworkCanary;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = DefaultDeadLinkNotificationServiceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.datasource.url=jdbc:h2:mem:dead-link-notices;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test"
        })
class DefaultDeadLinkNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T01:30:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 27);
    private static final LocalDateTime DIED = LocalDateTime.of(2026, 10, 27, 1, 14);
    private static final LocalDateTime DIED_BEFORE = LocalDateTime.of(2026, 10, 19, 23, 30);
    private static final LocalDateTime BEFORE_TONIGHT = LocalDateTime.of(2026, 10, 26, 22, 59);

    @Autowired
    private DeadLinkNotificationService deadLinkNotificationService;

    @Autowired
    private DestinationCheckService destinationCheckService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private DestinationCheckRepository destinationCheckRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Autowired
    private NotificationConsentRepository consentRepository;

    @Autowired
    private NotificationMessages messages;

    @Autowired
    private NotificationPayloads payloads;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private NotificationDeliveryService deliveryService;

    @MockitoBean
    private DestinationProber destinationProber;

    @MockitoBean
    private NetworkCanary networkCanary;

    @MockitoBean
    private HostPacer.Sleeper hostPacerSleeper;

    private User ana;
    private User bob;

    @BeforeEach
    void setUp() {
        ana = user("ana@example.com");
        bob = user("bob@example.com");
    }

    @AfterEach
    void tearDown() {
        clock.set(NOW);
        appProperties.getDestinationCheck().setCatchUpOnStartup(false);
        jdbcTemplate.update("delete from notification_claim");
        jdbcTemplate.update("delete from notification_consent");
        jdbcTemplate.update("delete from notification_channel");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void deadLinkEmailNeedsConsent() {
        emailConsent(ana);
        final var anasLink = dead(link(ana, "anas-menu"));
        final var bobsLink = dead(link(bob, "bobs-menu"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(anasLink, bobsLink), List.of(), NIGHT));

        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new DeadLinksEmail(ana.getId(), "ana@example.com", List.of(anasLink), "https://app.hrva.cc/dashboard"));
    }

    @Test
    void aRevokedDeadLinkEmailConsentSendsNothing() {
        emailConsent(ana);
        jdbcTemplate.update("update notification_consent set revoked_at = ?, revoked_source = 'SETTINGS' where user_id = ?", DIED, ana.getId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(dead(link(ana, "anas-menu"))), List.of(), NIGHT));

        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
    }

    @Test
    void aDeactivatedOwnerGetsNoDeadLinkEmail() {
        emailConsent(ana);
        final var link = dead(link(ana, "anas-menu"));
        jdbcTemplate.update("update user_account set active = false where id = ?", ana.getId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT));

        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
    }

    @Test
    void oneEmailPerOwnerPerNight() {
        emailConsent(ana);
        final var first = dead(link(ana, "first"));
        final var second = dead(link(ana, "second"));
        final var third = dead(link(ana, "third"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(third, first, second), List.of(), NIGHT));

        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService, times(1)).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue().links()).containsExactly(first, second, third);
    }

    @Test
    void anonymousLinksNotifyNobody() {
        final var anonymous = new DeadLink(link(null, "anonymous").getId(), null, "anonymous", "example.org", DIED, "404", null);

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(anonymous), List.of(anonymous), NIGHT));

        verifyNoInteractions(sendingEmailService, deliveryService);
    }

    @Test
    void aDeletedLinkIsSkipped() {
        emailConsent(ana);
        final var kept = dead(link(ana, "kept"));
        final var deleted = dead(link(ana, "deleted"));
        final var deletedAlone = dead(link(bob, "bobs-deleted"));
        emailConsent(bob);
        jdbcTemplate.update("delete from url where id in (?, ?)", deleted.urlId(), deletedAlone.urlId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(kept, deleted, deletedAlone), List.of(), NIGHT));

        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue().userId()).isEqualTo(ana.getId());
        assertThat(sent.getValue().links()).containsExactly(kept);
    }

    @Test
    void aLinkWhoseDestinationWasEditedBeforeTheNoticeIsSkipped() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var kept = dead(link(ana, "kept"));
        final var edited = dead(link(ana, "edited"));
        final var editedAlone = dead(link(bob, "bobs-edited"));
        emailConsent(bob);
        channel(bob, NotificationType.LINK_DEAD);
        jdbcTemplate.update("update url set destination_dead_since = null, destination_last_status = null, destination_fail_streak = 0 "
                + "where id in (?, ?)", edited.urlId(), editedAlone.urlId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(kept, edited, editedAlone), List.of(), NIGHT));

        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue().userId()).isEqualTo(ana.getId());
        assertThat(sent.getValue().links()).containsExactly(kept);
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), any(), content.capture());
        assertThat(content.getValue()).containsOnlyKeys(ana.getId());
        assertThat(content.getValue().get(ana.getId())).isEqualTo(new ChannelContent(payloads.linkDead(List.of(kept)), messages.linkDead(List.of(kept))));
    }

    @Test
    void aRecoveredLinkNeedsOnlyToStillExist() {
        channel(ana, NotificationType.LINK_BACK);
        final var back = back(link(ana, "anas-menu"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(), List.of(back), NIGHT));

        verify(deliveryService).enqueue(eq(NotificationType.LINK_BACK), eq("link.back:2026-10-27"), any(), anyMap());
    }

    @Test
    void manyLinksAreReadInChunksOfAThousand() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var links = urlRepository.saveAllAndFlush(IntStream.rangeClosed(1, 1001)
                .mapToObj(index -> Url.builder().shortUrl("many" + index).longUrl("https://many" + index + ".example.org/page").owner(ana).active(true).build())
                .toList());
        jdbcTemplate.update("update url set destination_dead_since = ?, destination_last_status = '404' where owner_id = ?", DIED, ana.getId());
        final var deadLinks = links.stream()
                .map(url -> new DeadLink(url.getId(), ana.getId(), url.getShortUrl(), url.getShortUrl() + ".example.org", DIED, "404", null))
                .toList();
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        deadLinkNotificationService.notify(new DestinationCheckOutcome(deadLinks, List.of(), NIGHT));

        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue().links()).hasSize(1001);
        assertThat(java.util.Arrays.stream(statistics.getQueries()).filter(query -> query.contains("destinationDeadSince is not null")))
                .singleElement()
                .satisfies(query -> assertThat(statistics.getQueryStatistics(query).getExecutionCount()).isEqualTo(2));
    }

    @Test
    void linkDeadGoesToChannelsWithTheEvent() {
        final var withEvent = channel(ana, NotificationType.LINK_DEAD);
        channel(ana, NotificationType.LINK_BACK);
        channel(bob, NotificationType.DAILY_SUMMARY);
        final var link = dead(link(ana, "anas-menu"));
        final var bobsLink = dead(link(bob, "bobs-menu"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link, bobsLink), List.of(), NIGHT));

        final var channels = channelsCaptor();
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), channels.capture(), content.capture());
        verify(deliveryService, never()).enqueue(eq(NotificationType.LINK_BACK), anyString(), any(), anyMap());
        assertThat(channels.getValue()).extracting(NotificationChannel::getId).containsExactly(withEvent.getId());
        assertThat(content.getValue()).containsOnlyKeys(ana.getId());
        assertThat(content.getValue().get(ana.getId())).isEqualTo(new ChannelContent(payloads.linkDead(List.of(link)), messages.linkDead(List.of(link))));
        assertThat(content.getValue().get(ana.getId()).message().title()).isEqualTo("hrva.cc: 1 link looks broken");
        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
    }

    @Test
    void aChannelWithoutTheEventsConsentGetsNothing() {
        final var revoked = channel(ana, NotificationType.LINK_DEAD);
        jdbcTemplate.update("update notification_consent set revoked_at = ?, revoked_source = 'SETTINGS' where channel_id = ?", DIED, revoked.getId());
        final var switchedOff = channel(ana, NotificationType.LINK_DEAD);
        jdbcTemplate.update("update notification_channel set enabled = false where id = ?", switchedOff.getId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(dead(link(ana, "anas-menu"))), List.of(), NIGHT));

        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aDeactivatedOwnersChannelsGetNothing() {
        channel(ana, NotificationType.LINK_DEAD);
        channel(ana, NotificationType.LINK_BACK);
        final var link = dead(link(ana, "anas-menu"));
        final var back = back(link(ana, "anas-shop"));
        jdbcTemplate.update("update user_account set active = false where id = ?", ana.getId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(back), NIGHT));

        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void recoveredGoesToLinkBackChannelsOnly() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var withEvent = channel(ana, NotificationType.LINK_BACK);
        final var back = back(link(ana, "anas-menu"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(), List.of(back), NIGHT));

        final var channels = channelsCaptor();
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.LINK_BACK), eq("link.back:2026-10-27"), channels.capture(), content.capture());
        verify(deliveryService, never()).enqueue(eq(NotificationType.LINK_DEAD), anyString(), any(), anyMap());
        assertThat(channels.getValue()).extracting(NotificationChannel::getId).containsExactly(withEvent.getId());
        assertThat(content.getValue().get(ana.getId()))
                .isEqualTo(new ChannelContent(payloads.linkBack(List.of(back), NIGHT), messages.linkBack(List.of(back))));
        assertThat(payloads.linkBack(List.of(back), NIGHT).links()).singleElement()
                .satisfies(data -> assertThat(data.backOn()).isEqualTo("2026-10-27"));
        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
    }

    @Test
    void nothingNewSendsNothing() {
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        deadLinkNotificationService.notify(DestinationCheckOutcome.nothing(NIGHT));

        verifyNoInteractions(sendingEmailService, deliveryService);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void aSecondNoticeTheSameNightSendsNoSecondEmail() {
        emailConsent(ana);
        final var link = dead(link(ana, "anas-menu"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT));
        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT));

        verify(sendingEmailService, times(1)).sendDeadLinksEmail(any());
        assertThat(jdbcTemplate.queryForList("select claim_key from notification_claim where user_id = ?", String.class, ana.getId()))
                .containsExactly("dead-email:2026-10-27");
    }

    @Test
    void theNextNightsNoticeEmailsAgain() {
        emailConsent(ana);
        final var link = dead(link(ana, "anas-menu"));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT));
        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT.plusDays(1)));

        verify(sendingEmailService, times(2)).sendDeadLinksEmail(any());
    }

    @Test
    void aLinkThatDiedEarlierTonightIsCaughtUpWhenTheCheckRunsAgain() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var link = checkedTonight(dead(link(ana, "anas-menu")));

        destinationCheckService.runNightly();

        verifyNoInteractions(destinationProber);
        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new DeadLinksEmail(ana.getId(), "ana@example.com", List.of(link), "https://app.hrva.cc/dashboard"));
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), any(), content.capture());
        assertThat(content.getValue()).containsOnlyKeys(ana.getId());
        assertThat(content.getValue().get(ana.getId())).isEqualTo(new ChannelContent(payloads.linkDead(List.of(link)), messages.linkDead(List.of(link))));
    }

    @Test
    void aCaughtUpLinkIsNotEmailedTwiceTheSameNight() {
        emailConsent(ana);
        final var link = dead(link(ana, "anas-menu"));
        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT));
        checkedTonight(link);

        destinationCheckService.runNightly();

        verify(sendingEmailService, times(1)).sendDeadLinksEmail(any());
    }

    @Test
    void aLinkThatDiesInThisRunIsListedOnce() {
        emailConsent(ana);
        final var url = link(ana, "anas-menu");
        jdbcTemplate.update("update url set destination_fail_streak = 2 where id = ?", url.getId());
        when(destinationProber.probe(eq(url.getLongUrl()), any(), any())).thenReturn(new ProbeResult(ProbeOutcome.DEAD, "404", 404));

        destinationCheckService.runNightly();

        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue().links()).singleElement().satisfies(link -> assertThat(link.urlId()).isEqualTo(url.getId()));
    }

    @Test
    void aLinkTheOwnerResetSinceItDiedTonightIsNotCaughtUp() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var link = checkedTonight(dead(link(ana, "anas-menu")));
        destinationCheckRepository.reset(link.urlId());
        when(destinationProber.probe(any(), any(), any())).thenReturn(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));

        destinationCheckService.runNightly();

        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aLinkThatDiedOnAnEarlierNightIsNotCaughtUp() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var url = link(ana, "anas-menu");
        jdbcTemplate.update("update url set destination_dead_since = ?, destination_last_status = '404', destination_fail_streak = 3, "
                + "destination_checked_at = ? where id = ?", BEFORE_TONIGHT, DIED, url.getId());

        destinationCheckService.runNightly();

        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aDeletedLinkThatDiedTonightIsNotCaughtUp() {
        emailConsent(ana);
        final var link = checkedTonight(dead(link(ana, "anas-menu")));
        jdbcTemplate.update("delete from url where id = ?", link.urlId());

        destinationCheckService.runNightly();

        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
    }

    @Test
    void aRestartCatchesUpALinkThatDiedEarlierTonight() {
        appProperties.getDestinationCheck().setCatchUpOnStartup(true);
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var link = checkedTonight(dead(link(ana, "anas-menu")));

        destinationCheckService.catchUpAfterStartup();

        verifyNoInteractions(destinationProber);
        final var sent = ArgumentCaptor.forClass(DeadLinksEmail.class);
        verify(sendingEmailService).sendDeadLinksEmail(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new DeadLinksEmail(ana.getId(), "ana@example.com", List.of(link), "https://app.hrva.cc/dashboard"));
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), any(), content.capture());
        assertThat(content.getValue().get(ana.getId())).isEqualTo(new ChannelContent(payloads.linkDead(List.of(link)), messages.linkDead(List.of(link))));
    }

    @Test
    void aSecondStartupTheSameNightSendsNoSecondEmail() {
        appProperties.getDestinationCheck().setCatchUpOnStartup(true);
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        checkedTonight(dead(link(ana, "anas-menu")));

        destinationCheckService.catchUpAfterStartup();
        clock.advance(java.time.Duration.ofMinutes(20));
        destinationCheckService.catchUpAfterStartup();

        verify(sendingEmailService, times(1)).sendDeadLinksEmail(any());
        verify(deliveryService, times(2)).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), any(), anyMap());
    }

    @Test
    void aStartupAtNoonAfterTheNightsRunSendsNothingNew() {
        appProperties.getDestinationCheck().setCatchUpOnStartup(true);
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var url = link(ana, "anas-menu");
        jdbcTemplate.update("update url set destination_fail_streak = 2 where id = ?", url.getId());
        when(destinationProber.probe(eq(url.getLongUrl()), any(), any())).thenReturn(new ProbeResult(ProbeOutcome.DEAD, "404", 404));
        destinationCheckService.runNightly();

        clock.set(Instant.parse("2026-10-27T10:00:00Z"));
        destinationCheckService.catchUpAfterStartup();

        verify(sendingEmailService, times(1)).sendDeadLinksEmail(any());
        verify(deliveryService, times(2)).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), any(), anyMap());
        verify(deliveryService, never()).enqueue(eq(NotificationType.LINK_DEAD), argThat(key -> !"link.dead:2026-10-27".equals(key)), any(), anyMap());
    }

    @Test
    void aLinkDeactivatedAfterItDiedGetsNoNotice() {
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var link = dead(link(ana, "anas-menu"));
        jdbcTemplate.update("update url set active = false where id = ?", link.urlId());

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(link), List.of(), NIGHT));

        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aLinkDeactivatedAfterItDiedTonightIsNotCaughtUp() {
        appProperties.getDestinationCheck().setCatchUpOnStartup(true);
        emailConsent(ana);
        channel(ana, NotificationType.LINK_DEAD);
        final var link = checkedTonight(dead(link(ana, "anas-menu")));
        jdbcTemplate.update("update url set active = false where id = ?", link.urlId());

        destinationCheckService.catchUpAfterStartup();

        assertThat(destinationCheckRepository.findDiedSince(LocalDateTime.of(2026, 10, 26, 23, 0))).isEmpty();
        verify(sendingEmailService, never()).sendDeadLinksEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void oneOwnersFailedEmailDoesNotStopTheOthersOrTheChannels() {
        emailConsent(ana);
        emailConsent(bob);
        channel(bob, NotificationType.LINK_DEAD);
        final var anasLink = dead(link(ana, "anas-menu"));
        final var bobsLink = dead(link(bob, "bobs-menu"));
        doThrow(new IllegalStateException("queue full")).when(sendingEmailService)
                .sendDeadLinksEmail(argThat(email -> email != null && email.userId() == ana.getId()));

        deadLinkNotificationService.notify(new DestinationCheckOutcome(List.of(anasLink, bobsLink), List.of(), NIGHT));

        verify(sendingEmailService).sendDeadLinksEmail(argThat(email -> email != null && email.userId() == bob.getId()));
        verify(deliveryService).enqueue(eq(NotificationType.LINK_DEAD), eq("link.dead:2026-10-27"), any(), anyMap());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<NotificationChannel>> channelsCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<Long, ChannelContent>> contentCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private User user(final String email) {
        return userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
    }

    private Url link(final User owner, final String alias) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(alias).longUrl("https://" + alias + ".example.org/page").owner(owner).active(true).build());
    }

    private DeadLink dead(final Url url) {
        jdbcTemplate.update("update url set destination_dead_since = ?, destination_last_status = '404', destination_fail_streak = 3 where id = ?",
                DIED, url.getId());
        return new DeadLink(url.getId(), url.getOwner().getId(), url.getShortUrl(), url.getShortUrl() + ".example.org", DIED, "404", null);
    }

    private DeadLink checkedTonight(final DeadLink link) {
        jdbcTemplate.update("update url set destination_checked_at = ? where id = ?", DIED, link.urlId());
        return link;
    }

    private static DeadLink back(final Url url) {
        return new DeadLink(url.getId(), url.getOwner().getId(), url.getShortUrl(), url.getShortUrl() + ".example.org", DIED_BEFORE, null, null);
    }

    private void emailConsent(final User owner) {
        consentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).type(NotificationType.DEAD_LINK_EMAIL)
                .grantedAt(DIED.minusDays(1)).source(ConsentSource.SETTINGS).build());
    }

    private NotificationChannel channel(final User owner, final NotificationType event) {
        final var channel = channelRepository.saveAndFlush(NotificationChannel.builder().owner(owner).name("Team alerts").type(ChannelType.DISCORD)
                .enabled(true).targetSealed("v1:sealed").maskedTarget("https://discord.com/••••abcd")
                .createdAt(DIED).updatedAt(DIED).build());
        consentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).channelId(channel.getId()).channelLabel("Discord: Team alerts")
                .type(event).grantedAt(DIED.minusDays(1)).source(ConsentSource.SETTINGS).build());
        return channel;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, UserRepository.class, NotificationConsentRepository.class, NotificationChannelRepository.class}))
    @Import({DefaultDeadLinkNotificationService.class, EmailConsentReader.class, NotificationMessages.class, NotificationPayloads.class,
            NotificationClaimRepository.class, DefaultDestinationCheckService.class, DestinationCheckRepository.class, AnalyticsDays.class,
            ShortLinks.class, TestApplication.Fixed.class})
    static class TestApplication {

        @TestConfiguration
        static class Fixed {

            @Bean
            MutableClock clock() {
                return new MutableClock(NOW);
            }

            @Bean
            AppProperties appProperties() {
                final var appProperties = new AppProperties();
                appProperties.getDestinationCheck().setCatchUpOnStartup(false);
                return appProperties;
            }

        }

    }

}
