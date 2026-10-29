package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultDeadLinkNotificationService;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
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

    @Autowired
    private DeadLinkNotificationService deadLinkNotificationService;

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

    private User ana;
    private User bob;

    @BeforeEach
    void setUp() {
        ana = user("ana@example.com");
        bob = user("bob@example.com");
    }

    @AfterEach
    void tearDown() {
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
        final var anonymous = new DeadLink(link(null, "anonymous").getId(), null, "anonymous", "example.org", DIED, "404");

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

    private static DeadLink dead(final Url url) {
        return new DeadLink(url.getId(), url.getOwner().getId(), url.getShortUrl(), url.getShortUrl() + ".example.org", DIED, "404");
    }

    private static DeadLink back(final Url url) {
        return new DeadLink(url.getId(), url.getOwner().getId(), url.getShortUrl(), url.getShortUrl() + ".example.org", DIED_BEFORE, null);
    }

    private void emailConsent(final User owner) {
        consentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).type(NotificationType.DEAD_LINK_EMAIL)
                .grantedAt(DIED.minusDays(1)).source(ConsentSource.SETTINGS).build());
    }

    private NotificationChannel channel(final User owner, final NotificationType event) {
        final var channel = channelRepository.saveAndFlush(NotificationChannel.builder().owner(owner).name("Team alerts").type(ChannelType.DISCORD)
                .enabled(true).targetSealed("v1:sealed").maskedTarget("https://discord.com/••••abcd").build());
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
            TestApplication.Fixed.class})
    static class TestApplication {

        @TestConfiguration
        static class Fixed {

            @Bean
            Clock clock() {
                return Clock.fixed(NOW, ZoneOffset.UTC);
            }

            @Bean
            AppProperties appProperties() {
                return new AppProperties();
            }

        }

    }

}
