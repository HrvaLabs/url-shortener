package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.dto.DataExportDto.ChannelExport;
import cc.hrva.urlshortener.dto.DataExportDto.ConsentExport;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.NotificationDelivery;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.DeliveryStatus;
import cc.hrva.urlshortener.model.enums.LastDeliveryStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.NotificationDeliveryRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = NotificationAccountDataTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:notify-account-data;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class NotificationAccountDataTest {

    private static final LocalDateTime GRANTED = LocalDateTime.of(2026, 10, 20, 8, 15, 2);
    private static final LocalDateTime REVOKED = LocalDateTime.of(2026, 10, 24, 19, 0, 41);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 21, 9, 0);
    private static final String SEALED_TARGET = "v1:SEALEDTARGETxq81";
    private static final String SEALED_ACCESS_TOKEN = "v1:SEALEDACCESSzz07";
    private static final String SEALED_SIGNING_SECRET = "v1:SEALEDSIGNINGpp42";
    private static final String TOPIC_URL = "https://ntfy.sh/hrva-private-topic-9f3a";
    private static final String MASK = "https://ntfy.sh/••••9f3a";

    @Autowired
    private NotificationAccountData notificationAccountData;

    @Autowired
    private NotificationChannelRepository notificationChannelRepository;

    @Autowired
    private NotificationConsentRepository notificationConsentRepository;

    @Autowired
    private NotificationDeliveryRepository notificationDeliveryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void exportHasTheConsentHistoryInOrder() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var channel = savedChannel(ana, ChannelType.DISCORD, "Team alerts", "https://discord.com/••••abcd");
        consent(ana, null, null, NotificationType.WEEKLY_SUMMARY_EMAIL, GRANTED, REVOKED, ConsentSource.UNSUBSCRIBE_LINK);
        consent(ana, channel.getId(), "Discord: Team alerts", NotificationType.WEEKLY_SUMMARY, REVOKED, null, null);
        consent(ana, null, null, NotificationType.WEEKLY_SUMMARY_EMAIL, REVOKED, null, null);
        consent(ana, channel.getId(), "Discord: Team alerts", NotificationType.LINK_BACK, GRANTED, REVOKED, ConsentSource.SETTINGS);
        consent(ana, channel.getId(), "Discord: Team alerts", NotificationType.LINK_DEAD, GRANTED.plusMinutes(1), null, null);
        consent(ana, null, "Slack: Old", NotificationType.DAILY_SUMMARY, GRANTED.plusMinutes(2), REVOKED, ConsentSource.CHANNEL_REMOVED);
        consent(bob, null, null, NotificationType.DEAD_LINK_EMAIL, GRANTED, null, null);
        entityManager.clear();

        final var export = notificationAccountData.exportFor(userRepository.findById(ana.getId()).orElseThrow());

        assertThat(export.consentHistory()).containsExactly(
                new ConsentExport(NotificationType.WEEKLY_SUMMARY_EMAIL, null, GRANTED, ConsentSource.SETTINGS, REVOKED,
                        ConsentSource.UNSUBSCRIBE_LINK),
                new ConsentExport(NotificationType.LINK_BACK, "Discord: Team alerts", GRANTED, ConsentSource.SETTINGS, REVOKED,
                        ConsentSource.SETTINGS),
                new ConsentExport(NotificationType.LINK_DEAD, "Discord: Team alerts", GRANTED.plusMinutes(1), ConsentSource.SETTINGS, null,
                        null),
                new ConsentExport(NotificationType.DAILY_SUMMARY, "Slack: Old", GRANTED.plusMinutes(2), ConsentSource.SETTINGS, REVOKED,
                        ConsentSource.CHANNEL_REMOVED),
                new ConsentExport(NotificationType.WEEKLY_SUMMARY, "Discord: Team alerts", REVOKED, ConsentSource.SETTINGS, null, null),
                new ConsentExport(NotificationType.WEEKLY_SUMMARY_EMAIL, null, REVOKED, ConsentSource.SETTINGS, null, null));
        assertThat(export.channels()).containsExactly(new ChannelExport("Team alerts", ChannelType.DISCORD, true,
                List.of(NotificationType.LINK_DEAD, NotificationType.WEEKLY_SUMMARY), "https://discord.com/••••abcd", null, CREATED, null, null, null,
                0));
    }

    @Test
    void exportListsChannelsOldestFirstWithTheirLastDelivery() {
        final var ana = savedUser("ana@example.com");
        final var later = savedChannel(ana, ChannelType.SLACK, "Later", "https://hooks.slack.com/••••wxyz");
        final var earlier = savedChannel(ana, ChannelType.TELEGRAM, "Earlier", "Bot ••••WxYz");
        earlier.setEnabled(false);
        earlier.setLastDeliveryAt(REVOKED);
        earlier.setLastDeliveryStatus(LastDeliveryStatus.FAILED);
        earlier.setChatId("-1001234567890");
        earlier.setAutoDisabledAt(REVOKED);
        earlier.setFailedDays(10);
        notificationChannelRepository.saveAndFlush(earlier);
        entityManager.createQuery("update NotificationChannel ch set ch.createdAt = :at where ch.id = :id")
                .setParameter("at", CREATED.minusDays(1)).setParameter("id", earlier.getId()).executeUpdate();
        savedChannel(savedUser("bob@example.com"), ChannelType.DISCORD, "Bob's", "https://discord.com/••••bobs");
        entityManager.clear();

        final var export = notificationAccountData.exportFor(userRepository.findById(ana.getId()).orElseThrow());

        assertThat(export.channels()).containsExactly(
                new ChannelExport("Earlier", ChannelType.TELEGRAM, false, List.of(), "Bot ••••WxYz", "-1001234567890", CREATED.minusDays(1),
                        REVOKED, LastDeliveryStatus.FAILED, REVOKED, 10),
                new ChannelExport("Later", ChannelType.SLACK, true, List.of(), "https://hooks.slack.com/••••wxyz", null, later.getCreatedAt(),
                        null, null, null, 0));
    }

    @Test
    void exportHasNoSecrets() {
        final var ana = savedUser("ana@example.com");
        final var channel = notificationChannelRepository.saveAndFlush(NotificationChannel.builder()
                .owner(ana)
                .name("Phone")
                .type(ChannelType.NTFY)
                .enabled(true)
                .targetSealed(SEALED_TARGET)
                .accessTokenSealed(SEALED_ACCESS_TOKEN)
                .signingSecretSealed(SEALED_SIGNING_SECRET)
                .maskedTarget(MASK)
                .createdAt(CREATED)
                .updatedAt(CREATED)
                .build());
        consent(ana, channel.getId(), "ntfy: Phone", NotificationType.LINK_DEAD, GRANTED, null, null);
        notificationDeliveryRepository.saveAndFlush(NotificationDelivery.builder()
                .uid(UUID.randomUUID().toString())
                .channelId(channel.getId())
                .event(NotificationType.LINK_DEAD)
                .dedupeKey("link.dead:2026-10-27")
                .body("{\"event\":\"link.dead\",\"target\":\"" + TOPIC_URL + "\"}")
                .message("hrva.cc: 1 link looks broken " + TOPIC_URL)
                .status(DeliveryStatus.GAVE_UP)
                .lastError("Couldn't reach the channel.")
                .createdAt(GRANTED)
                .build());
        entityManager.clear();

        final var mapper = JsonMapper.builder().build();
        final var json = mapper.writeValueAsString(notificationAccountData.exportFor(userRepository.findById(ana.getId()).orElseThrow()));
        final var export = mapper.readTree(json);

        assertThat(json)
                .doesNotContain(SEALED_TARGET, SEALED_ACCESS_TOKEN, SEALED_SIGNING_SECRET, "SEALED", "v1:")
                .doesNotContain(TOPIC_URL, "hrva-private-topic")
                .doesNotContainIgnoringCase("sealed")
                .doesNotContain("\"deliveries\"", "\"body\"", "\"message\"", "\"uid\"", "link.dead:", "Couldn't reach")
                .doesNotContain("\"accessToken\"", "\"signingSecret\"", "\"target\"", "\"id\"");
        assertThat(export.get("channels").get(0).propertyNames()).containsExactly(
                "name", "type", "enabled", "events", "maskedTarget", "chatId", "createdAt", "lastDeliveryAt", "lastDeliveryStatus",
                "autoDisabledAt", "failedDays");
        assertThat(export.at("/channels/0/maskedTarget").asString()).isEqualTo(MASK);
    }

    @Test
    void anAccountWithoutNotificationsExportsEmptyLists() {
        final var ana = savedUser("ana@example.com");
        entityManager.clear();

        final var export = notificationAccountData.exportFor(userRepository.findById(ana.getId()).orElseThrow());

        assertThat(export.consentHistory()).isEmpty();
        assertThat(export.channels()).isEmpty();
    }

    private User savedUser(final String email) {
        return userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
    }

    private NotificationChannel savedChannel(final User owner, final ChannelType type, final String name, final String mask) {
        final var channel = notificationChannelRepository.saveAndFlush(NotificationChannel.builder()
                .owner(owner)
                .name(name)
                .type(type)
                .enabled(true)
                .targetSealed(SEALED_TARGET)
                .maskedTarget(mask)
                .createdAt(CREATED)
                .updatedAt(CREATED)
                .build());
        entityManager.createQuery("update NotificationChannel ch set ch.createdAt = :at where ch.id = :id")
                .setParameter("at", CREATED).setParameter("id", channel.getId()).executeUpdate();
        entityManager.refresh(channel);
        return channel;
    }

    private void consent(final User owner, final Long channelId, final String channelLabel, final NotificationType type,
                         final LocalDateTime grantedAt, final LocalDateTime revokedAt, final ConsentSource revokedSource) {
        notificationConsentRepository.saveAndFlush(NotificationConsent.builder()
                .owner(owner)
                .channelId(channelId)
                .channelLabel(channelLabel)
                .type(type)
                .grantedAt(grantedAt)
                .source(ConsentSource.SETTINGS)
                .revokedAt(revokedAt)
                .revokedSource(revokedSource)
                .build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = NotificationChannel.class)
    @Import({NotificationAccountData.class, NotificationClaimRepository.class})
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
    }

}
