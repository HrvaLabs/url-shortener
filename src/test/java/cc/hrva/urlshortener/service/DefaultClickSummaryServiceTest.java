package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.dto.notification.DailySummary.SummaryLink;
import cc.hrva.urlshortener.dto.notification.WeeklySummary;
import cc.hrva.urlshortener.dto.notification.WeeklySummary.EndingSoon;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultClickSummaryService;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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

@SpringBootTest(
        classes = DefaultClickSummaryServiceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.datasource.url=jdbc:h2:mem:click-summaries;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test"
        })
class DefaultClickSummaryServiceTest {

    private static final Instant AFTER_MIDNIGHT_IN_ZAGREB = Instant.parse("2026-10-26T23:30:00Z");
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 26);
    private static final LocalDateTime GRANTED = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final Instant MONDAY_MORNING = Instant.parse("2026-10-26T08:00:00Z");
    private static final LocalDateTime MONDAY_MORNING_UTC = LocalDateTime.of(2026, 10, 26, 8, 0);
    private static final LocalDate LAST_MONDAY = LocalDate.of(2026, 10, 19);
    private static final MutableClock CLOCK = new MutableClock(AFTER_MIDNIGHT_IN_ZAGREB);
    private static final long WEEKLY_STATEMENTS = 10;
    private static final long DAILY_STATEMENTS = 5;

    @Autowired
    private ClickSummaryService clickSummaryService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Autowired
    private NotificationConsentRepository consentRepository;

    @Autowired
    private LinkStatsRepository linkStatsRepository;

    @Autowired
    private NotificationMessages messages;

    @MockitoSpyBean
    private NotificationPayloads payloads;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private NotificationDeliveryService deliveryService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    private User ana;
    private User bob;

    @BeforeEach
    void setUp() {
        CLOCK.set(AFTER_MIDNIGHT_IN_ZAGREB);
        ana = user("ana@example.com");
        bob = user("bob@example.com");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from notification_claim");
        jdbcTemplate.update("delete from notification_consent");
        jdbcTemplate.update("delete from notification_channel");
        jdbcTemplate.update("delete from link_daily_stats");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void dailySummaryOfYesterday() {
        final var channel = channel(ana, NotificationType.DAILY_SUMMARY);
        final var link = link(ana, "menu");
        clicks(link, YESTERDAY.minusDays(1), 99, 90, 9);
        clicks(link, YESTERDAY, 5, 4, 1);
        clicks(link, YESTERDAY.plusDays(1), 77, 70, 7);

        clickSummaryService.sendDaily();

        final var expected = new DailySummary(YESTERDAY, 5, 4, 1, List.of(new SummaryLink("menu", "menu.example.org", 5, 4, 1, null)));
        final var channels = channelsCaptor();
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.DAILY_SUMMARY), eq("summary.daily:2026-10-26"), channels.capture(), content.capture());
        assertThat(channels.getValue()).extracting(NotificationChannel::getId).containsExactly(channel.getId());
        assertThat(content.getValue()).containsExactly(Map.entry(ana.getId(), new ChannelContent(payloads.daily(expected), messages.daily(expected))));
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void aDayWithoutClicksSendsNothing() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        final var link = link(ana, "menu");
        clicks(link, YESTERDAY.minusDays(1), 99, 90, 9);
        clicks(link, YESTERDAY, 0, 0, 0);

        clickSummaryService.sendDaily();

        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aDayWithOnlyBotClicksStillSends() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        clicks(link(ana, "menu"), YESTERDAY, 0, 0, 3);

        clickSummaryService.sendDaily();

        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.DAILY_SUMMARY), eq("summary.daily:2026-10-26"), any(), content.capture());
        assertThat(content.getValue().get(ana.getId()).message().lines()).first().isEqualTo("0 clicks from 0 visitors, and 3 bot clicks");
    }

    @Test
    void topTwentyOnly() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        IntStream.rangeClosed(1, 25).forEach(index -> clicks(link(ana, "link" + index), YESTERDAY, index, 1, 0));

        clickSummaryService.sendDaily();

        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.DAILY_SUMMARY), anyString(), any(), content.capture());
        final var data = (NotificationPayloads.DailyData) content.getValue().get(ana.getId()).data();
        assertThat(data.links()).hasSize(20);
        assertThat(data.links().getFirst().shortUrl()).isEqualTo("https://hrva.cc/link25");
        assertThat(data.links().getLast().shortUrl()).isEqualTo("https://hrva.cc/link6");
        assertThat(data.totals()).isEqualTo(new NotificationPayloads.DailyTotals(325, 25, 0));
        assertThat(content.getValue().get(ana.getId()).message().lines()).hasSize(21);
    }

    @Test
    void onlyOwnersWithTheEvent() {
        final var anasChannel = channel(ana, NotificationType.DAILY_SUMMARY);
        channel(bob, NotificationType.LINK_DEAD);
        final var cid = user("cid@example.com");
        clicks(link(ana, "anas"), YESTERDAY, 2, 2, 0);
        clicks(link(bob, "bobs"), YESTERDAY, 3, 3, 0);
        clicks(link(cid, "cids"), YESTERDAY, 4, 4, 0);

        clickSummaryService.sendDaily();

        final var channels = channelsCaptor();
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.DAILY_SUMMARY), anyString(), channels.capture(), content.capture());
        assertThat(channels.getValue()).extracting(NotificationChannel::getId).containsExactly(anasChannel.getId());
        assertThat(content.getValue()).containsOnlyKeys(ana.getId());
    }

    @Test
    void aRevokedDailySummarySendsNothing() {
        final var channel = channel(ana, NotificationType.DAILY_SUMMARY);
        jdbcTemplate.update("update notification_consent set revoked_at = ?, revoked_source = 'SETTINGS' where channel_id = ?", GRANTED.plusDays(1), channel.getId());
        clicks(link(ana, "menu"), YESTERDAY, 5, 4, 1);

        clickSummaryService.sendDaily();

        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aSwitchedOffChannelGetsNothing() {
        final var channel = channel(ana, NotificationType.DAILY_SUMMARY);
        jdbcTemplate.update("update notification_channel set enabled = false where id = ?", channel.getId());
        clicks(link(ana, "menu"), YESTERDAY, 5, 4, 1);

        clickSummaryService.sendDaily();

        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aDeactivatedOwnerGetsNoDailySummary() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        clicks(link(ana, "menu"), YESTERDAY, 5, 4, 1);
        jdbcTemplate.update("update user_account set active = false where id = ?", ana.getId());

        clickSummaryService.sendDaily();

        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
    }

    @Test
    void aRerunEnqueuesUnderTheSameDedupeKey() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        clicks(link(ana, "menu"), YESTERDAY, 5, 4, 1);

        clickSummaryService.sendDaily();
        CLOCK.set(AFTER_MIDNIGHT_IN_ZAGREB.plusSeconds(3600));
        clickSummaryService.sendDaily();

        verify(deliveryService, times(2)).enqueue(eq(NotificationType.DAILY_SUMMARY), eq("summary.daily:2026-10-26"), any(), anyMap());
    }

    @Test
    void oneOwnersFailedDailySummaryDoesNotStopTheOthers() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        channel(bob, NotificationType.DAILY_SUMMARY);
        clicks(link(ana, "anas"), YESTERDAY, 5, 4, 1);
        clicks(link(bob, "bobs"), YESTERDAY, 3, 3, 0);
        doThrow(new IllegalStateException("broken")).when(payloads)
                .daily(argThat(summary -> summary != null && summary.links().stream().anyMatch(link -> "anas".equals(link.shortUrl()))));

        clickSummaryService.sendDaily();

        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.DAILY_SUMMARY), eq("summary.daily:2026-10-26"), any(), content.capture());
        assertThat(content.getValue()).containsOnlyKeys(bob.getId());
    }

    @Test
    void theDailySummaryRunsOutsideAnyTransaction() throws NoSuchMethodException {
        assertThat(DefaultClickSummaryService.class.getMethod("sendDaily").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(DefaultClickSummaryService.class.getMethod("sendDailyInBackground").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void weeklyEmailNeedsConsent() {
        weeklyEmailConsent(ana);
        final var cid = user("cid@example.com");
        final var cidsChannel = channel(cid, NotificationType.WEEKLY_SUMMARY);
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        clicks(link(bob, "bobs"), LAST_MONDAY, 3, 3, 0);
        clicks(link(cid, "cids"), LAST_MONDAY, 4, 4, 0);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries()).extracting(WeeklySummary::userId).containsExactly(ana.getId());
        assertThat(sentWeeklySummaries().getFirst().email()).isEqualTo("ana@example.com");
        final var channels = channelsCaptor();
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.WEEKLY_SUMMARY), eq("summary.weekly:2026-10-19"), channels.capture(), content.capture());
        assertThat(channels.getValue()).extracting(NotificationChannel::getId).containsExactly(cidsChannel.getId());
        assertThat(content.getValue()).containsOnlyKeys(cid.getId());
    }

    @Test
    void aRevokedWeeklyEmailConsentSendsNothing() {
        weeklyEmailConsent(ana);
        jdbcTemplate.update("update notification_consent set revoked_at = ?, revoked_source = 'UNSUBSCRIBE_LINK' where user_id = ?", GRANTED.plusDays(1), ana.getId());
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService, never()).sendWeeklySummaryEmail(any());
        assertThat(claims()).isZero();
    }

    @Test
    void aDeactivatedOwnerGetsNoWeeklySummary() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        jdbcTemplate.update("update user_account set active = false where id = ?", ana.getId());
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService, never()).sendWeeklySummaryEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
        assertThat(claims()).isZero();
    }

    @Test
    void anEmptyWeekSendsNothing() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        final var link = link(ana, "anas");
        clicks(link, LAST_MONDAY.minusDays(1), 9, 9, 0);
        clicks(link, LAST_MONDAY.plusDays(7), 9, 9, 0);
        clicks(link, LAST_MONDAY, 0, 0, 5);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService, never()).sendWeeklySummaryEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
        assertThat(claims()).isZero();
    }

    @Test
    void aDeadOnlyWeekSends() {
        weeklyEmailConsent(ana);
        final var firstNight = dead(link(ana, "first"), LocalDateTime.of(2026, 10, 18, 22, 30), "404");
        final var lastNight = dead(link(ana, "last"), LocalDateTime.of(2026, 10, 25, 22, 30), "410");
        dead(link(ana, "before"), LocalDateTime.of(2026, 10, 18, 21, 30), "404");
        dead(link(ana, "after"), LocalDateTime.of(2026, 10, 25, 23, 30), "404");
        final var inactive = dead(link(ana, "inactive"), LocalDateTime.of(2026, 10, 21, 2, 0), "404");
        jdbcTemplate.update("update url set active = false where id = ?", inactive.getId());
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.clicks()).isEqualTo(new WeeklyDigest.Change(0, 0));
            assertThat(summary.deadLinks()).containsExactly(
                    new DeadLink(firstNight.getId(), ana.getId(), "first", "first.example.org", LocalDateTime.of(2026, 10, 18, 22, 30), "404", null),
                    new DeadLink(lastNight.getId(), ana.getId(), "last", "last.example.org", LocalDateTime.of(2026, 10, 25, 22, 30), "410", null));
        });
    }

    @Test
    void endingSoonRules() {
        weeklyEmailConsent(ana);
        final var inSixDays = ending(ana, "six-days", MONDAY_MORNING_UTC.plusDays(6), null, 0L);
        ending(ana, "eight-days", MONDAY_MORNING_UTC.plusDays(8), null, 0L);
        ending(ana, "expired", MONDAY_MORNING_UTC.minusDays(1), null, 0L);
        final var nineLeft = ending(ana, "nine-left", null, 100L, 91L);
        final var tenLeft = ending(ana, "ten-left", null, 100L, 90L);
        ending(ana, "eleven-left", null, 100L, 89L);
        final var oneLeft = ending(ana, "one-left", null, 5L, 4L);
        ending(ana, "none-left", null, 5L, 5L);
        final var neverVisited = ending(ana, "never-visited", null, 1L, null);
        final var inactive = ending(ana, "inactive", MONDAY_MORNING_UTC.plusDays(2), null, 0L);
        jdbcTemplate.update("update url set active = false where id = ?", inactive.getId());
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries()).singleElement().satisfies(summary -> assertThat(summary.endingSoon()).containsExactly(
                new EndingSoon("six-days", "six-days.example.org", "EXPIRES", LocalDate.of(2026, 11, 1), null, null),
                new EndingSoon("nine-left", "nine-left.example.org", "VISIT_LIMIT", null, 9L, null),
                new EndingSoon("ten-left", "ten-left.example.org", "VISIT_LIMIT", null, 10L, null),
                new EndingSoon("one-left", "one-left.example.org", "VISIT_LIMIT", null, 1L, null),
                new EndingSoon("never-visited", "never-visited.example.org", "VISIT_LIMIT", null, 1L, null)));
        assertThat(List.of(inSixDays, nineLeft, tenLeft, oneLeft, neverVisited)).extracting(Url::getId).isSorted();
    }

    @Test
    void theClaimIsOncePerWeek() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();
        clickSummaryService.sendWeekly();

        verify(sendingEmailService, times(1)).sendWeeklySummaryEmail(any());
        verify(deliveryService, times(2)).enqueue(eq(NotificationType.WEEKLY_SUMMARY), eq("summary.weekly:2026-10-19"), any(), anyMap());
        assertThat(jdbcTemplate.queryForList("select claim_key from notification_claim where user_id = ?", String.class, ana.getId()))
                .containsExactly("weekly-email:2026-10-19");
    }

    @Test
    void aLostClaimSendsNoEmailButTheChannelsStillGetTheirs() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        jdbcTemplate.update("insert into notification_claim (user_id, claim_key, created_at) values (?, 'weekly-email:2026-10-19', ?)", ana.getId(), GRANTED);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService, never()).sendWeeklySummaryEmail(any());
        verify(deliveryService).enqueue(eq(NotificationType.WEEKLY_SUMMARY), eq("summary.weekly:2026-10-19"), any(), anyMap());
    }

    @Test
    void aFailedEmailHandOffKeepsTheChannelDeliveryAndTheOthers() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        weeklyEmailConsent(bob);
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        clicks(link(bob, "bobs"), LAST_MONDAY, 3, 3, 0);
        doThrow(new IllegalStateException("queue full")).when(sendingEmailService)
                .sendWeeklySummaryEmail(argThat(summary -> summary != null && summary.userId() == ana.getId()));
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService).sendWeeklySummaryEmail(argThat(summary -> summary != null && summary.userId() == bob.getId()));
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.WEEKLY_SUMMARY), eq("summary.weekly:2026-10-19"), any(), content.capture());
        assertThat(content.getValue()).containsOnlyKeys(ana.getId());
    }

    @Test
    void aFailedChannelContentKeepsTheEmailAndTheOthers() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        channel(bob, NotificationType.WEEKLY_SUMMARY);
        clicks(link(ana, "anas"), LAST_MONDAY, 2, 2, 0);
        clicks(link(bob, "bobs"), LAST_MONDAY, 3, 3, 0);
        doThrow(new IllegalStateException("broken")).when(payloads)
                .weekly(argThat(summary -> summary != null && summary.userId() == ana.getId()));
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService).sendWeeklySummaryEmail(argThat(summary -> summary != null && summary.userId() == ana.getId()));
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.WEEKLY_SUMMARY), eq("summary.weekly:2026-10-19"), any(), content.capture());
        assertThat(content.getValue()).containsOnlyKeys(bob.getId());
    }

    @Test
    void moreThanTwentyDeadAndEndingLinksAreCountedNotDropped() {
        weeklyEmailConsent(ana);
        IntStream.rangeClosed(1, 23).forEach(index -> dead(link(ana, "dead" + index), LocalDateTime.of(2026, 10, 20, 2, index), "404"));
        IntStream.rangeClosed(1, 22).forEach(index -> ending(ana, "soon" + index, MONDAY_MORNING_UTC.plusDays(3).plusMinutes(index), null, 0L));
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.deadLinks()).hasSize(20).extracting(DeadLink::shortUrl).startsWith("dead1").endsWith("dead20");
            assertThat(summary.moreDeadLinks()).isEqualTo(3);
            assertThat(summary.endingSoon()).hasSize(20).extracting(EndingSoon::shortUrl).startsWith("soon1").endsWith("soon20");
            assertThat(summary.moreEndingSoon()).isEqualTo(2);
        });
    }

    @Test
    void exactlyTwentyHaveNoMore() {
        weeklyEmailConsent(ana);
        IntStream.rangeClosed(1, 20).forEach(index -> dead(link(ana, "dead" + index), LocalDateTime.of(2026, 10, 20, 2, index), "404"));
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.deadLinks()).hasSize(20);
            assertThat(summary.moreDeadLinks()).isZero();
            assertThat(summary.moreEndingSoon()).isZero();
        });
    }

    @Test
    void noLinksNoSummary() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.WEEKLY_SUMMARY);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        verify(sendingEmailService, never()).sendWeeklySummaryEmail(any());
        verify(deliveryService, never()).enqueue(any(), anyString(), any(), anyMap());
        assertThat(claims()).isZero();
    }

    @Test
    void weekIsTheLastClosedMondayToSunday() {
        weeklyEmailConsent(ana);
        final var link = link(ana, "menu");
        clicks(link, LAST_MONDAY.minusDays(1), 5, 5, 0);
        clicks(link, LAST_MONDAY, 3, 2, 1);
        clicks(link, LAST_MONDAY.plusDays(6), 4, 3, 0);
        clicks(link, LAST_MONDAY.plusDays(7), 50, 50, 0);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries()).containsExactly(new WeeklySummary(ana.getId(), "ana@example.com", LAST_MONDAY, LAST_MONDAY.plusDays(6),
                new WeeklyDigest.Change(7, 5), 5, List.of(new WeeklyDigest.TopLink("menu", "menu.example.org", 7, null)), List.of(), List.of()));
    }

    @Test
    void topLinksAreTheFiveBusiest() {
        weeklyEmailConsent(ana);
        IntStream.rangeClosed(1, 6).forEach(index -> clicks(link(ana, "link" + index), LAST_MONDAY, index, 1, 0));
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        assertThat(sentWeeklySummaries().getFirst().topLinks()).extracting(WeeklyDigest.TopLink::shortUrl)
                .containsExactly("link6", "link5", "link4", "link3", "link2");
    }

    @Test
    void theWeeklyChannelContentIsTheSummaryWithoutTheEmailAddress() {
        final var channel = channel(ana, NotificationType.WEEKLY_SUMMARY);
        clicks(link(ana, "menu"), LAST_MONDAY, 3, 2, 0);
        CLOCK.set(MONDAY_MORNING);

        clickSummaryService.sendWeekly();

        final var expected = new WeeklySummary(ana.getId(), "ana@example.com", LAST_MONDAY, LAST_MONDAY.plusDays(6), new WeeklyDigest.Change(3, 0), 2,
                List.of(new WeeklyDigest.TopLink("menu", "menu.example.org", 3, null)), List.of(), List.of());
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.WEEKLY_SUMMARY), eq("summary.weekly:2026-10-19"),
                argThat(channels -> channels.stream().map(NotificationChannel::getId).toList().equals(List.of(channel.getId()))), content.capture());
        assertThat(content.getValue()).containsExactly(Map.entry(ana.getId(), new ChannelContent(payloads.weekly(expected), messages.weekly(expected))));
        verify(sendingEmailService, never()).sendWeeklySummaryEmail(any());
    }

    @Test
    void summariesCarryTheHostname() {
        weeklyEmailConsent(ana);
        channel(ana, NotificationType.DAILY_SUMMARY);
        final var dotCom = domain(ana, "go.example.com");
        final var dotOrg = domain(ana, "go.example.org");
        final var dotNet = domain(ana, "go.example.net");
        clicks(onDomain(link(ana, "menu"), dotCom), LAST_MONDAY, 5, 4, 0);
        clicks(onDomain(link(ana, "cafe"), dotOrg), LAST_MONDAY, 4, 4, 0);
        clicks(link(ana, "shop"), LAST_MONDAY, 3, 3, 0);
        clicks(onDomain(link(ana, "bar"), dotNet), LAST_MONDAY, 2, 2, 0);
        final var dead = onDomain(dead(link(ana, "dead"), LocalDateTime.of(2026, 10, 22, 1, 0), "404"), dotNet);
        onDomain(ending(ana, "soon", MONDAY_MORNING_UTC.plusDays(3), null, 0L), dotOrg);
        CLOCK.set(MONDAY_MORNING);
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        clickSummaryService.sendWeekly();

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(WEEKLY_STATEMENTS);
        assertThat(sentWeeklySummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.topLinks()).containsExactly(new WeeklyDigest.TopLink("menu", "menu.example.org", 5, "go.example.com"),
                    new WeeklyDigest.TopLink("cafe", "cafe.example.org", 4, "go.example.org"), new WeeklyDigest.TopLink("shop", "shop.example.org", 3, null),
                    new WeeklyDigest.TopLink("bar", "bar.example.org", 2, "go.example.net"));
            assertThat(summary.deadLinks()).containsExactly(
                    new DeadLink(dead.getId(), ana.getId(), "dead", "dead.example.org", LocalDateTime.of(2026, 10, 22, 1, 0), "404", "go.example.net"));
            assertThat(summary.endingSoon()).extracting(EndingSoon::shortUrl, EndingSoon::domain).containsExactly(tuple("soon", "go.example.org"));
        });

        CLOCK.set(Instant.parse("2026-10-19T23:30:00Z"));
        statistics.clear();
        clickSummaryService.sendDaily();

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(DAILY_STATEMENTS);
        final var content = contentCaptor();
        verify(deliveryService).enqueue(eq(NotificationType.DAILY_SUMMARY), eq("summary.daily:2026-10-19"), any(), content.capture());
        final var data = (NotificationPayloads.DailyData) content.getValue().get(ana.getId()).data();
        assertThat(data.links()).extracting(NotificationPayloads.DailyLinkData::shortUrl)
                .containsExactly("https://go.example.com/menu", "https://go.example.org/cafe", "https://hrva.cc/shop", "https://go.example.net/bar");
    }

    @Test
    void theWeeklySummaryRunsOutsideAnyTransaction() throws NoSuchMethodException {
        assertThat(DefaultClickSummaryService.class.getMethod("sendWeekly").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(DefaultClickSummaryService.class.getMethod("sendWeeklyInBackground").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private List<WeeklySummary> sentWeeklySummaries() {
        final var sent = ArgumentCaptor.forClass(WeeklySummary.class);
        verify(sendingEmailService, org.mockito.Mockito.atLeast(0)).sendWeeklySummaryEmail(sent.capture());
        return sent.getAllValues();
    }

    private long claims() {
        return jdbcTemplate.queryForObject("select count(*) from notification_claim", Long.class);
    }

    private void weeklyEmailConsent(final User owner) {
        consentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).type(NotificationType.WEEKLY_SUMMARY_EMAIL)
                .grantedAt(GRANTED).source(ConsentSource.SETTINGS).build());
    }

    private Url dead(final Url link, final LocalDateTime deadSince, final String status) {
        jdbcTemplate.update("update url set destination_dead_since = ?, destination_last_status = ?, destination_fail_streak = 3 where id = ?",
                deadSince, status, link.getId());
        return link;
    }

    private Url ending(final User owner, final String alias, final LocalDateTime expiresAt, final Long visitLimit, final Long visits) {
        final var link = urlRepository.saveAndFlush(Url.builder().shortUrl(alias).longUrl("https://" + alias + ".example.org/page").owner(owner)
                .active(true).expirationDate(expiresAt).visitLimit(visitLimit).build());
        jdbcTemplate.update("update url set visits = ? where id = ?", visits, link.getId());
        return link;
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
        return urlRepository.saveAndFlush(Url.builder().shortUrl(alias).longUrl("https://" + alias + ".example.org/page").owner(owner)
                .active(true).visits(0L).build());
    }

    private long domain(final User owner, final String hostname) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, ?, ?, 'ACTIVE', 'Menu links for my cafe', current_timestamp, current_timestamp)""", owner.getId(), hostname, hostname);
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname_key = ?", Long.class, hostname);
    }

    private Url onDomain(final Url link, final long domainId) {
        jdbcTemplate.update("update url set domain_id = ? where id = ?", domainId, link.getId());
        return link;
    }

    private void clicks(final Url link, final LocalDate day, final long clicks, final long uniqueVisitors, final long botClicks) {
        linkStatsRepository.incrementDailyStats(link.getId(), day, clicks, uniqueVisitors, botClicks);
    }

    private NotificationChannel channel(final User owner, final NotificationType event) {
        final var channel = channelRepository.saveAndFlush(NotificationChannel.builder().owner(owner).name("Team alerts").type(ChannelType.DISCORD)
                .enabled(true).targetSealed("v1:sealed").maskedTarget("https://discord.com/••••abcd")
                .createdAt(GRANTED).updatedAt(GRANTED).build());
        consentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).channelId(channel.getId()).channelLabel("Discord: Team alerts")
                .type(event).grantedAt(GRANTED).source(ConsentSource.SETTINGS).build());
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
    @Import({DefaultClickSummaryService.class, LinkStatsRepository.class, NotificationClaimRepository.class, EmailConsentReader.class,
            NotificationMessages.class, NotificationPayloads.class, ShortLinks.class, AnalyticsDays.class, TestApplication.Fixed.class})
    static class TestApplication {

        @TestConfiguration
        static class Fixed {

            @Bean
            MutableClock clock() {
                return CLOCK;
            }

            @Bean
            AppProperties appProperties() {
                return new AppProperties();
            }

        }

    }

}
