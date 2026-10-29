package cc.hrva.urlshortener.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cc.hrva.urlshortener.client.ChannelPoster;
import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.NotificationDeliveryRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultNotificationDeliveryService;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import cc.hrva.urlshortener.service.notification.NotificationTestKeys;
import cc.hrva.urlshortener.service.notification.SecretBox;
import cc.hrva.urlshortener.service.notification.channel.ChannelSenders;
import cc.hrva.urlshortener.service.notification.channel.DiscordChannelSender;
import cc.hrva.urlshortener.service.notification.channel.NtfyChannelSender;
import cc.hrva.urlshortener.service.notification.channel.SlackChannelSender;
import cc.hrva.urlshortener.service.notification.channel.TelegramChannelSender;
import cc.hrva.urlshortener.service.notification.channel.WebhookChannelSender;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = DefaultNotificationDeliveryServiceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:notify-deliveries;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test"
        })
class DefaultNotificationDeliveryServiceTest {

    private static final Instant START = Instant.parse("2026-10-27T08:00:00Z");
    private static final LocalDateTime START_UTC = LocalDateTime.of(2026, 10, 27, 8, 0);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final String DISCORD_URL = "https://discord.com/api/webhooks/1/discord-secret";
    private static final String WEBHOOK_URL = "https://hooks.example.org/hrva";
    private static final String NOT_SENT = "Not sent: the channel is off or no longer gets this event.";
    private static final SecretBox SECRET_BOX = new SecretBox(NotificationTestKeys.keys());
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final FakePoster POSTER = new FakePoster();

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Autowired
    private NotificationClaimRepository claimRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationMessages messages;

    @Autowired
    private NotificationPayloads payloads;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationChannelService channelService;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(DefaultNotificationDeliveryService.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private User ana;

    @BeforeEach
    void setUp() {
        CLOCK.set(START);
        POSTER.reset();
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).build());
        when(channelService.openTarget(any())).thenAnswer(invocation -> {
            final NotificationChannel channel = invocation.getArgument(0);
            final var context = SecretBox.contextFor(channel.getOwner().getId());
            return new ChannelTarget(SECRET_BOX.open(channel.getTargetSealed(), context), null, channel.getChatId(),
                    channel.getSigningSecretSealed() == null ? null : SECRET_BOX.open(channel.getSigningSecretSealed(), context));
        });
        logEvents.start();
        serviceLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logEvents);
        jdbcTemplate.update("delete from notification_claim");
        jdbcTemplate.update("delete from notification_delivery");
        jdbcTemplate.update("delete from notification_consent");
        jdbcTemplate.update("delete from notification_channel");
        jdbcTemplate.update("delete from user_account where email = 'ana@example.com'");
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"LINK_DEAD", "LINK_BACK", "DAILY_SUMMARY", "WEEKLY_SUMMARY"})
    void deliveryNeedsActiveEventConsent(final NotificationType event) {
        final var withoutConsent = channel(ChannelType.DISCORD);
        final var other = event == NotificationType.LINK_DEAD ? NotificationType.LINK_BACK : NotificationType.LINK_DEAD;
        consent(withoutConsent, other);
        final var withConsent = channel(ChannelType.DISCORD);
        consent(withConsent, event);

        assertThat(enqueue(event, "key:1", withoutConsent, withConsent)).isEqualTo(1);
        assertThat(deliveryRepository.findAll()).singleElement().satisfies(row -> assertThat(row.getChannelId()).isEqualTo(withConsent.getId()));

        revoke(withConsent, event);
        deliveryService.drain();

        assertThat(POSTER.posts).isEmpty();
        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("LAST_ERROR", NOT_SENT).containsEntry("ATTEMPTS", 0)
                .containsEntry("FINISHED_AT", java.sql.Timestamp.valueOf(START_UTC));
        assertThat(channelRow(withConsent)).containsEntry("FAILED_DAYS", 0).containsEntry("LAST_DELIVERY_AT", null).containsEntry("ENABLED", true);
    }

    @Test
    void disabledChannelIsNotSent() {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);
        enqueue(NotificationType.LINK_DEAD, "key:1", channel);
        jdbcTemplate.update("update notification_channel set enabled = false where id = ?", channel.getId());

        deliveryService.drain();

        assertThat(POSTER.posts).isEmpty();
        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("LAST_ERROR", NOT_SENT);
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 0);
    }

    @Test
    void aDisabledChannelIsNotQueued() {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);
        channel.setEnabled(false);

        assertThat(enqueue(NotificationType.LINK_DEAD, "key:1", channel)).isZero();
        assertThat(deliveryRepository.count()).isZero();
    }

    @Test
    void anOwnerWithoutContentIsNotQueued() {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);

        assertThat(deliveryService.enqueue(NotificationType.LINK_DEAD, "key:1", List.of(channel), Map.of())).isZero();
        assertThat(deliveryService.enqueue(NotificationType.LINK_DEAD, "key:1", List.of(), Map.of())).isZero();
    }

    @Test
    void enqueueStoresTheContractBodyAndTheMessage() {
        final var channel = channel(ChannelType.WEBHOOK);
        consent(channel, NotificationType.LINK_DEAD);

        enqueue(NotificationType.LINK_DEAD, "link.dead:2026-10-27", channel);

        final var row = deliveryRepository.findAll().getFirst();
        assertThat(row.getUid()).matches("^[0-9a-f-]{36}$");
        assertThat(row.getStatus().name()).isEqualTo("PENDING");
        assertThat(row.getNextAttemptAt()).isEqualTo(START_UTC);
        assertThat(row.getCreatedAt()).isEqualTo(START_UTC);
        assertThat(row.getEvent()).isEqualTo(NotificationType.LINK_DEAD);
        assertThat(row.getBody()).startsWith("{\"event\":\"link.dead\",\"deliveryId\":\"" + row.getUid() + "\",\"createdAt\":\"2026-10-27T08:00:00Z\",\"links\":[")
                .contains("\"shortUrl\":\"https://hrva.cc/abc\"");
        assertThat(jsonMapper.readValue(row.getMessage(), NotificationMessage.class)).isEqualTo(message());
    }

    @Test
    void successMarksSentAndResetsFailures() {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);
        jdbcTemplate.update("update notification_channel set failed_days = 3, last_failed_day = ? where id = ?", TODAY.minusDays(1), channel.getId());
        enqueue(NotificationType.LINK_DEAD, "key:1", channel);
        CLOCK.advance(Duration.ofSeconds(5));

        deliveryService.drain();

        assertThat(POSTER.posts).singleElement().satisfies(post -> {
            assertThat(post.url()).isEqualTo(DISCORD_URL);
            assertThat(post.body()).contains("**hrva.cc: 1 link looks broken**", "\"flags\":4");
        });
        assertThat(row("key:1")).containsEntry("STATUS", "SENT").containsEntry("ATTEMPTS", 1).containsEntry("LAST_HTTP", 204)
                .containsEntry("LAST_ERROR", null).containsEntry("CLAIMED_AT", null)
                .containsEntry("FINISHED_AT", java.sql.Timestamp.valueOf(START_UTC.plusSeconds(5)));
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 0).containsEntry("LAST_FAILED_DAY", null)
                .containsEntry("LAST_DELIVERY_STATUS", "SENT").containsEntry("LAST_DELIVERY_HTTP", 204).containsEntry("LAST_DELIVERY_ERROR", null)
                .containsEntry("LAST_DELIVERY_AT", java.sql.Timestamp.valueOf(START_UTC.plusSeconds(5)));
    }

    @Test
    void serverErrorRetriesOnSchedule() {
        final var channel = channel(ChannelType.WEBHOOK);
        consent(channel, NotificationType.LINK_DEAD);
        enqueue(NotificationType.LINK_DEAD, "key:1", channel);
        POSTER.always(new PostResult(503, null));

        deliveryService.drain();
        assertThat(row("key:1")).containsEntry("STATUS", "PENDING").containsEntry("ATTEMPTS", 1).containsEntry("LAST_HTTP", 503)
                .containsEntry("LAST_ERROR", "The channel answered with HTTP 503. Check the URL.")
                .containsEntry("NEXT_ATTEMPT_AT", java.sql.Timestamp.valueOf(START_UTC.plusMinutes(1)));
        assertThat(channelRow(channel)).containsEntry("LAST_DELIVERY_STATUS", "FAILED").containsEntry("LAST_DELIVERY_HTTP", 503).containsEntry("FAILED_DAYS", 0);

        CLOCK.advance(Duration.ofSeconds(59));
        deliveryService.drain();
        assertThat(POSTER.posts).hasSize(1);

        CLOCK.advance(Duration.ofSeconds(1));
        deliveryService.drain();
        assertThat(row("key:1")).containsEntry("ATTEMPTS", 2).containsEntry("NEXT_ATTEMPT_AT", java.sql.Timestamp.valueOf(START_UTC.plusMinutes(11)));

        CLOCK.advance(Duration.ofMinutes(10));
        deliveryService.drain();
        assertThat(row("key:1")).containsEntry("ATTEMPTS", 3).containsEntry("NEXT_ATTEMPT_AT", java.sql.Timestamp.valueOf(START_UTC.plusMinutes(71)));

        CLOCK.advance(Duration.ofMinutes(60));
        deliveryService.drain();
        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("ATTEMPTS", 4)
                .containsEntry("FINISHED_AT", java.sql.Timestamp.valueOf(START_UTC.plusMinutes(71)));
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 1).containsEntry("LAST_FAILED_DAY", java.sql.Date.valueOf(TODAY));

        CLOCK.advance(Duration.ofHours(2));
        deliveryService.drain();
        assertThat(POSTER.posts).hasSize(4);
        assertThat(POSTER.posts).extracting(Post::body).containsOnly(POSTER.posts.getFirst().body());
        assertThat(POSTER.posts).extracting(post -> post.headers().get("X-Hrva-Signature")).containsOnly(POSTER.posts.getFirst().headers().get("X-Hrva-Signature"));
        assertThat(POSTER.posts).extracting(post -> post.headers().get("X-Hrva-Delivery")).containsOnly(deliveryRepository.findAll().getFirst().getUid());
    }

    @Test
    void clientErrorGivesUpAtOnce() {
        final var channel = queuedDiscord("key:1");
        POSTER.always(new PostResult(404, null));

        deliveryService.drain();

        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("ATTEMPTS", 1).containsEntry("LAST_HTTP", 404)
                .containsEntry("LAST_ERROR", "The channel answered with HTTP 404. Check the URL.");
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 1).containsEntry("LAST_DELIVERY_STATUS", "FAILED")
                .containsEntry("LAST_DELIVERY_ERROR", "The channel answered with HTTP 404. Check the URL.");
    }

    @Test
    void redirectGivesUpAtOnce() {
        queuedDiscord("key:1");
        POSTER.always(new PostResult(302, "redirected"));

        deliveryService.drain();

        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("ATTEMPTS", 1)
                .containsEntry("LAST_ERROR", "The channel redirected the request. Paste the final URL.");
    }

    @Test
    void anAddressThatIsNoLongerPublicGivesUpAtOnce() {
        queuedDiscord("key:1");
        POSTER.always(new PostResult(0, "not-allowed"));

        deliveryService.drain();

        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("LAST_HTTP", null);
    }

    @Test
    void tooManyRequestsRetries() {
        queuedDiscord("key:1");
        POSTER.always(new PostResult(429, null));

        deliveryService.drain();

        assertThat(row("key:1")).containsEntry("STATUS", "PENDING").containsEntry("ATTEMPTS", 1);
    }

    @Test
    void timeoutsAndUnreachableChannelsAndRequestTimeoutsRetry() {
        queuedDiscord("key:1");
        queuedDiscord("key:2");
        queuedDiscord("key:3");
        POSTER.answers(new PostResult(0, "timeout"), new PostResult(0, "error"), new PostResult(408, null));

        deliveryService.drain();

        assertThat(List.of(row("key:1"), row("key:2"), row("key:3"))).allSatisfy(row -> assertThat(row).containsEntry("STATUS", "PENDING")
                .containsEntry("ATTEMPTS", 1));
        assertThat(row("key:1")).containsEntry("LAST_HTTP", null).containsEntry("LAST_ERROR", "Couldn't reach the channel: no answer within 10 seconds.");
    }

    @Test
    void givingUpCountsOncePerDay() {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);
        enqueue(NotificationType.LINK_DEAD, "key:1", channel);
        enqueue(NotificationType.LINK_DEAD, "key:2", channel);
        POSTER.always(new PostResult(404, null));

        deliveryService.drain();
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 1);

        CLOCK.advance(Duration.ofHours(14));
        enqueue(NotificationType.LINK_DEAD, "key:3", channel);
        deliveryService.drain();
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 1).containsEntry("LAST_FAILED_DAY", java.sql.Date.valueOf(TODAY));

        CLOCK.advance(Duration.ofHours(1));
        enqueue(NotificationType.LINK_DEAD, "key:4", channel);
        deliveryService.drain();
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 2).containsEntry("LAST_FAILED_DAY", java.sql.Date.valueOf(TODAY.plusDays(1)));
    }

    @Test
    void aSuccessBetweenFailedDaysStartsTheCountAgain() {
        final var channel = queuedDiscord("key:1");
        POSTER.always(new PostResult(404, null));
        deliveryService.drain();

        CLOCK.advance(Duration.ofDays(1));
        enqueue(NotificationType.LINK_DEAD, "key:2", channel);
        POSTER.always(new PostResult(204, null));
        deliveryService.drain();
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 0);

        CLOCK.advance(Duration.ofDays(1));
        enqueue(NotificationType.LINK_DEAD, "key:3", channel);
        POSTER.always(new PostResult(404, null));
        deliveryService.drain();
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 1);
    }

    @Test
    void tenFailedDaysTurnTheChannelOff() {
        final var channel = queuedDiscord("key:1");
        jdbcTemplate.update("update notification_channel set failed_days = 9, last_failed_day = ? where id = ?", TODAY.minusDays(3), channel.getId());
        POSTER.always(new PostResult(404, null));

        deliveryService.drain();

        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 10).containsEntry("ENABLED", false)
                .containsEntry("AUTO_DISABLED_AT", java.sql.Timestamp.valueOf(START_UTC));
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsOnlyOnce("Notification channel turned off after failing on 10 different days channel id=" + channel.getId());
    }

    @Test
    void nineFailedDaysKeepTheChannelOn() {
        final var channel = queuedDiscord("key:1");
        jdbcTemplate.update("update notification_channel set failed_days = 8, last_failed_day = ? where id = ?", TODAY.minusDays(1), channel.getId());
        POSTER.always(new PostResult(404, null));

        deliveryService.drain();

        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 9).containsEntry("ENABLED", true).containsEntry("AUTO_DISABLED_AT", null);
    }

    @Test
    void staleSendingRowsAreReleased() {
        final var channel = queuedDiscord("key:old");
        enqueue(NotificationType.LINK_DEAD, "key:recent", channel);
        jdbcTemplate.update("update notification_delivery set status = 'SENDING', claimed_at = ? where dedupe_key = 'key:old'", START_UTC.minusMinutes(11));
        jdbcTemplate.update("update notification_delivery set status = 'SENDING', claimed_at = ? where dedupe_key = 'key:recent'", START_UTC.minusMinutes(5));

        deliveryService.drain();

        assertThat(POSTER.posts).hasSize(1);
        assertThat(row("key:old")).containsEntry("STATUS", "SENT");
        assertThat(row("key:recent")).containsEntry("STATUS", "SENDING");
    }

    @Test
    void aDuplicateEnqueueIsSkipped() {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);
        final var second = channel(ChannelType.SLACK);
        consent(second, NotificationType.LINK_DEAD);

        assertThat(enqueue(NotificationType.LINK_DEAD, "link.dead:2026-10-27", channel)).isEqualTo(1);
        assertThat(enqueue(NotificationType.LINK_DEAD, "link.dead:2026-10-27", channel, second)).isEqualTo(1);
        assertThat(enqueue(NotificationType.LINK_DEAD, "link.dead:2026-10-27", channel, second)).isZero();

        assertThat(deliveryRepository.count()).isEqualTo(2);
    }

    @Test
    void deleteOldKeepsRecentRows() {
        final var channel = queuedDiscord("key:recent");
        enqueue(NotificationType.LINK_DEAD, "key:old", channel);
        jdbcTemplate.update("update notification_delivery set created_at = ? where dedupe_key = 'key:old'", START_UTC.minusDays(30).minusMinutes(1));
        jdbcTemplate.update("update notification_delivery set created_at = ? where dedupe_key = 'key:recent'", START_UTC.minusDays(29));
        claimRepository.claim(ana.getId(), "weekly-email:old", START_UTC.minusDays(60).minusMinutes(1));
        claimRepository.claim(ana.getId(), "weekly-email:recent", START_UTC.minusDays(59));

        deliveryService.deleteOld();

        assertThat(jdbcTemplate.queryForList("select dedupe_key from notification_delivery", String.class)).containsExactly("key:recent");
        assertThat(jdbcTemplate.queryForList("select claim_key from notification_claim", String.class)).containsExactly("weekly-email:recent");
    }

    @Test
    void anOverlappingRunIsSkipped() throws Exception {
        queuedDiscord("key:1");
        final var posting = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        POSTER.blockWith(posting, release);
        final var first = Thread.ofPlatform().start(deliveryService::drain);
        assertThat(posting.await(10, TimeUnit.SECONDS)).isTrue();

        deliveryService.drain();

        assertThat(POSTER.posts).hasSize(1);
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("Notification delivery drain skipped: the previous run is still going");
        release.countDown();
        first.join(10_000);
        assertThat(row("key:1")).containsEntry("STATUS", "SENT");
    }

    @Test
    void aChannelWhoseSecretCannotBeOpenedGivesUpAtOnce() {
        final var channel = queuedDiscord("key:1");
        doThrow(new IllegalStateException("sealed value can't be opened")).when(channelService).openTarget(any());

        deliveryService.drain();

        assertThat(POSTER.posts).isEmpty();
        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("ATTEMPTS", 1)
                .containsEntry("LAST_ERROR", "Couldn't send to the channel: a saved setting isn't valid. Edit the channel and save it again.");
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 1);
    }

    @Test
    void aDeactivatedOwnersDeliveryIsNotSent() {
        final var channel = queuedDiscord("key:1");
        jdbcTemplate.update("update user_account set active = false where id = ?", ana.getId());

        deliveryService.drain();

        assertThat(POSTER.posts).isEmpty();
        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("LAST_ERROR", NOT_SENT).containsEntry("ATTEMPTS", 0);
        assertThat(channelRow(channel)).containsEntry("FAILED_DAYS", 0).containsEntry("LAST_DELIVERY_AT", null).containsEntry("ENABLED", true);
    }

    @Test
    void anInvalidRequestGivesUpAtOnce() {
        queuedDiscord("key:1");
        POSTER.always(new PostResult(0, "invalid"));

        deliveryService.drain();

        assertThat(POSTER.posts).hasSize(1);
        assertThat(row("key:1")).containsEntry("STATUS", "GAVE_UP").containsEntry("ATTEMPTS", 1);
    }

    @Test
    void noTargetInAnyLogLine() {
        queuedDiscord("key:1");
        POSTER.always(new PostResult(404, null));

        deliveryService.drain();

        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage).allSatisfy(line -> assertThat(line).doesNotContain("discord", "secret"));
    }

    private NotificationChannel queuedDiscord(final String key) {
        final var channel = channel(ChannelType.DISCORD);
        consent(channel, NotificationType.LINK_DEAD);
        enqueue(NotificationType.LINK_DEAD, key, channel);
        return channel;
    }

    private int enqueue(final NotificationType event, final String key, final NotificationChannel... channels) {
        return deliveryService.enqueue(event, key, List.of(channels), Map.of(ana.getId(), new ChannelContent(payloads.linkDead(List.of(deadLink())), message())));
    }

    private NotificationMessage message() {
        return messages.linkDead(List.of(deadLink()));
    }

    private static DeadLink deadLink() {
        return new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 27, 1, 14), "404");
    }

    private NotificationChannel channel(final ChannelType type) {
        final var context = SecretBox.contextFor(ana.getId());
        final var target = type == ChannelType.WEBHOOK ? WEBHOOK_URL : type == ChannelType.SLACK ? "https://hooks.slack.com/services/T/B/x" : DISCORD_URL;
        return channelRepository.saveAndFlush(NotificationChannel.builder()
                .owner(ana)
                .name("Team alerts")
                .type(type)
                .enabled(true)
                .targetSealed(SECRET_BOX.seal(target, context))
                .signingSecretSealed(type == ChannelType.WEBHOOK ? SECRET_BOX.seal("signing-secret", context) : null)
                .maskedTarget("m")
                .createdAt(START_UTC)
                .updatedAt(START_UTC)
                .build());
    }

    private void consent(final NotificationChannel channel, final NotificationType event) {
        jdbcTemplate.update("insert into notification_consent (user_id, channel_id, channel_label, type, granted_at, source) values (?, ?, 'Discord: Team alerts', ?, ?, 'SETTINGS')",
                ana.getId(), channel.getId(), event.name(), START_UTC.minusDays(1));
    }

    private void revoke(final NotificationChannel channel, final NotificationType event) {
        jdbcTemplate.update("update notification_consent set revoked_at = ?, revoked_source = 'SETTINGS' where channel_id = ? and type = ?",
                START_UTC, channel.getId(), event.name());
    }

    private Map<String, Object> row(final String key) {
        return jdbcTemplate.queryForMap("select * from notification_delivery where dedupe_key = ?", key);
    }

    private Map<String, Object> channelRow(final NotificationChannel channel) {
        return jdbcTemplate.queryForMap("select * from notification_channel where id = ?", channel.getId());
    }

    private record Post(String url, Map<String, String> headers, String body) {
    }

    private static final class FakePoster implements ChannelPoster {

        private final List<Post> posts = Collections.synchronizedList(new ArrayList<>());
        private final Deque<PostResult> queued = new ArrayDeque<>();
        private PostResult always;
        private CountDownLatch posting;
        private CountDownLatch release;

        private void reset() {
            posts.clear();
            queued.clear();
            always = new PostResult(204, null);
            posting = null;
            release = null;
        }

        private void always(final PostResult result) {
            always = result;
        }

        private void answers(final PostResult... results) {
            queued.addAll(List.of(results));
        }

        private void blockWith(final CountDownLatch posting, final CountDownLatch release) {
            this.posting = posting;
            this.release = release;
        }

        @Override
        public PostResult post(final String url, final String contentType, final Map<String, String> headers, final byte[] body, final Duration timeout) {
            posts.add(new Post(url, headers, new String(body, StandardCharsets.UTF_8)));
            if (posting != null) {
                posting.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return queued.isEmpty() ? always : queued.poll();
        }

    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = NotificationChannel.class)
    @Import({DefaultNotificationDeliveryService.class, NotificationClaimRepository.class, ChannelSenders.class, DiscordChannelSender.class,
            SlackChannelSender.class, TelegramChannelSender.class, NtfyChannelSender.class, WebhookChannelSender.class, NotificationMessages.class,
            NotificationPayloads.class, AnalyticsDays.class, TestApplication.Fixed.class})
    @EnableJpaRepositories(
            basePackageClasses = NotificationChannelRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            NotificationChannelRepository.class,
                            NotificationConsentRepository.class,
                            NotificationDeliveryRepository.class,
                            UserRepository.class}))
    static class TestApplication {

        @TestConfiguration
        static class Fixed {

            @Bean
            MutableClock clock() {
                return CLOCK;
            }

            @Bean
            ChannelPoster channelPoster() {
                return POSTER;
            }

            @Bean
            AppProperties appProperties() {
                return new AppProperties();
            }

        }

    }

}
