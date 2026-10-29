package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.dto.notification.DailySummary.SummaryLink;
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
import cc.hrva.urlshortener.service.impl.DefaultClickSummaryService;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(
        classes = DefaultClickSummaryServiceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
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
    private static final MutableClock CLOCK = new MutableClock(AFTER_MIDNIGHT_IN_ZAGREB);

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

    @Autowired
    private NotificationPayloads payloads;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        final var expected = new DailySummary(YESTERDAY, 5, 4, 1, List.of(new SummaryLink("menu", "menu.example.org", 5, 4, 1)));
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
    void rerunIsDeduplicated() {
        channel(ana, NotificationType.DAILY_SUMMARY);
        clicks(link(ana, "menu"), YESTERDAY, 5, 4, 1);

        clickSummaryService.sendDaily();
        CLOCK.set(AFTER_MIDNIGHT_IN_ZAGREB.plusSeconds(3600));
        clickSummaryService.sendDaily();

        verify(deliveryService, times(2)).enqueue(eq(NotificationType.DAILY_SUMMARY), eq("summary.daily:2026-10-26"), any(), anyMap());
    }

    @Test
    void theDailySummaryRunsOutsideAnyTransaction() throws NoSuchMethodException {
        assertThat(DefaultClickSummaryService.class.getMethod("sendDaily").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(DefaultClickSummaryService.class.getMethod("sendDailyInBackground").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
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

    private void clicks(final Url link, final LocalDate day, final long clicks, final long uniqueVisitors, final long botClicks) {
        linkStatsRepository.incrementDailyStats(link.getId(), day, clicks, uniqueVisitors, botClicks);
    }

    private NotificationChannel channel(final User owner, final NotificationType event) {
        final var channel = channelRepository.saveAndFlush(NotificationChannel.builder().owner(owner).name("Team alerts").type(ChannelType.DISCORD)
                .enabled(true).targetSealed("v1:sealed").maskedTarget("https://discord.com/••••abcd").build());
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
            NotificationMessages.class, NotificationPayloads.class, AnalyticsDays.class, TestApplication.Fixed.class})
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
