package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.NotificationDelivery;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.DeliveryStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.service.notification.ChannelTargets;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = NotificationSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:notify-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class NotificationSchemaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 2, 0);
    private static final LocalDateTime TONIGHT = LocalDateTime.of(2026, 10, 26, 23, 0);

    @Autowired
    private NotificationChannelRepository notificationChannelRepository;

    @Autowired
    private NotificationConsentRepository notificationConsentRepository;

    @Autowired
    private NotificationDeliveryRepository notificationDeliveryRepository;

    @Autowired
    private NotificationClaimRepository notificationClaimRepository;

    @Autowired
    private DestinationCheckRepository destinationCheckRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailConsentReader emailConsentReader;

    @Autowired
    private EntityManager entityManager;

    @Test
    void everyChangesetOf031Ran() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '031-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("031-notification-channel", "EXECUTED"),
                        tuple("031-notification-consent", "EXECUTED"),
                        tuple("031-notification-delivery", "EXECUTED"),
                        tuple("031-notification-claim", "EXECUTED"),
                        tuple("031-url-destination-check", "EXECUTED"),
                        tuple("031-email-log-consent-type", "EXECUTED"),
                        tuple("031-notification-channel-masked-target", "EXECUTED"));
    }

    @Test
    void keysAndIndexesCarryTheirNames() {
        assertThat(jdbcTemplate.queryForList(
                "select upper(constraint_name) from information_schema.table_constraints where upper(table_name) like 'NOTIFICATION\\_%'", String.class))
                .contains(
                        "FK_NOTIFICATION_CHANNEL_USER",
                        "FK_NOTIFICATION_CONSENT_USER",
                        "FK_NOTIFICATION_CONSENT_CHANNEL",
                        "UQ_NOTIFICATION_DELIVERY_UID",
                        "UQ_NOTIFICATION_DELIVERY_CHANNEL_DEDUPE",
                        "FK_NOTIFICATION_DELIVERY_CHANNEL",
                        "PK_NOTIFICATION_CLAIM",
                        "FK_NOTIFICATION_CLAIM_USER");
        assertThat(jdbcTemplate.queryForList("select upper(index_name) from information_schema.indexes", String.class))
                .contains(
                        "IDX_NOTIFICATION_CHANNEL_USER_ID",
                        "IDX_NOTIFICATION_CONSENT_USER_TYPE",
                        "IDX_NOTIFICATION_CONSENT_CHANNEL_ID",
                        "IDX_NOTIFICATION_DELIVERY_DUE",
                        "IDX_NOTIFICATION_DELIVERY_CREATED_AT",
                        "IDX_NOTIFICATION_CLAIM_CREATED_AT",
                        "IDX_URL_DESTINATION_CHECKED_AT",
                        "IDX_URL_DESTINATION_DEAD_SINCE");
    }

    @Test
    void deletingTheAccountDeletesEverything() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var channel = savedChannel(ana, "Team alerts");
        notificationConsentRepository.saveAndFlush(emailConsent(ana, NotificationType.WEEKLY_SUMMARY_EMAIL).build());
        notificationConsentRepository.saveAndFlush(channelConsent(ana, channel, NotificationType.LINK_DEAD).build());
        notificationDeliveryRepository.saveAndFlush(delivery(channel, "link.dead:2026-10-27").build());
        notificationClaimRepository.claim(ana.getId(), "weekly-email:2026-10-19", NOW);
        notificationClaimRepository.claim(bob.getId(), "weekly-email:2026-10-19", NOW);
        entityManager.clear();

        jdbcTemplate.update("delete from user_account where id = ?", ana.getId());

        assertThat(rows("notification_channel")).isZero();
        assertThat(rows("notification_consent")).isZero();
        assertThat(rows("notification_delivery")).isZero();
        assertThat(jdbcTemplate.queryForList("select user_id from notification_claim", Long.class)).containsExactly(bob.getId());
    }

    @Test
    void deletingAChannelKeepsItsConsentHistory() {
        final var ana = savedUser("ana@example.com");
        final var channel = savedChannel(ana, "Team alerts");
        final var consent = notificationConsentRepository.saveAndFlush(channelConsent(ana, channel, NotificationType.LINK_DEAD).build());
        notificationDeliveryRepository.saveAndFlush(delivery(channel, "link.dead:2026-10-27").build());
        entityManager.clear();

        jdbcTemplate.update("delete from notification_channel where id = ?", channel.getId());

        assertThat(notificationConsentRepository.findById(consent.getId())).hasValueSatisfying(kept -> {
            assertThat(kept.getChannelId()).isNull();
            assertThat(kept.getChannelLabel()).isEqualTo("Discord: Team alerts");
            assertThat(kept.getType()).isEqualTo(NotificationType.LINK_DEAD);
        });
        assertThat(rows("notification_delivery")).isZero();
    }

    @Test
    void aMaskForTheLongestHostFits() {
        final var label = "a".repeat(63);
        final var host = String.join(".", label, label, label, "b".repeat(58)) + ".hr";
        final var mask = ChannelTargets.mask(ChannelType.WEBHOOK, "https://" + host + "/hook/abcd");
        final var channel = savedChannel(savedUser("ana@example.com"), "Long host");
        channel.setMaskedTarget(mask);
        notificationChannelRepository.saveAndFlush(channel);
        entityManager.clear();

        assertThat(notificationChannelRepository.findById(channel.getId())).hasValueSatisfying(saved -> assertThat(saved.getMaskedTarget()).isEqualTo(mask));
        assertThat(jdbcTemplate.queryForObject(
                "select character_maximum_length from information_schema.columns where upper(table_name) = 'NOTIFICATION_CHANNEL' and upper(column_name) = 'MASKED_TARGET'",
                Integer.class)).isEqualTo(ChannelTargets.MASK_LENGTH);
    }

    @Test
    void oneDeliveryPerChannelAndKey() {
        final var channel = savedChannel(savedUser("ana@example.com"), "Team alerts");
        notificationDeliveryRepository.saveAndFlush(delivery(channel, "summary.daily:2026-10-26").build());
        final var again = delivery(channel, "summary.daily:2026-10-26").build();

        assertThatThrownBy(() -> notificationDeliveryRepository.saveAndFlush(again))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        violation -> assertThat(violation.getMostSpecificCause().getMessage()).containsIgnoringCase("uq_notification_delivery_channel_dedupe"));
    }

    @Test
    void claimIsOnceOnly() {
        final var ana = savedUser("ana@example.com");

        assertThat(notificationClaimRepository.claim(ana.getId(), "weekly-email:2026-10-19", NOW)).isTrue();
        assertThat(notificationClaimRepository.claim(ana.getId(), "weekly-email:2026-10-19", NOW.plusMinutes(1))).isFalse();
        assertThat(notificationClaimRepository.claim(ana.getId(), "weekly-email:2026-10-26", NOW)).isTrue();
    }

    @Test
    void oldClaimsAreDeleted() {
        final var ana = savedUser("ana@example.com");
        notificationClaimRepository.claim(ana.getId(), "weekly-email:2026-08-17", NOW.minusDays(61));
        notificationClaimRepository.claim(ana.getId(), "weekly-email:2026-10-19", NOW);

        assertThat(notificationClaimRepository.deleteCreatedBefore(NOW.minusDays(60))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("select claim_key from notification_claim", String.class)).containsExactly("weekly-email:2026-10-19");
    }

    @Test
    void urlColumnsDefaultAndAreReadOnlyInJpa() {
        final var saved = savedLink("menu", savedUser("ana@example.com"));
        entityManager.clear();

        final var loaded = urlRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getDestinationFailStreak()).isZero();
        assertThat(loaded.getDestinationCheckedAt()).isNull();
        assertThat(loaded.getDestinationDeadSince()).isNull();
        assertThat(loaded.getDestinationLastStatus()).isNull();
        entityManager.clear();

        assertThat(destinationCheckRepository.record(saved.getId(), saved.getLongUrl(), NOW, 3, NOW, "404")).isTrue();
        loaded.setVisits(7L);
        urlRepository.saveAndFlush(loaded);
        entityManager.clear();

        final var reread = urlRepository.findById(saved.getId()).orElseThrow();
        assertThat(reread.getVisits()).isEqualTo(7L);
        assertThat(reread.getDestinationCheckedAt()).isEqualTo(NOW);
        assertThat(reread.getDestinationFailStreak()).isEqualTo(3);
        assertThat(reread.getDestinationDeadSince()).isEqualTo(NOW);
        assertThat(reread.getDestinationLastStatus()).isEqualTo("404");
    }

    @Test
    void findDueOrdersNeverCheckedFirstAndSkipsTonight() {
        final var ana = savedUser("ana@example.com");
        final var checkedYesterday = savedLink("yesterday", ana);
        final var neverChecked = savedLink("never", ana);
        final var checkedTonight = savedLink("tonight", ana);
        final var inactive = savedLink("inactive", ana);
        final var blocked = urlRepository.saveAndFlush(Url.builder().shortUrl("blocked").longUrl("https://example.org/blocked")
                .owner(ana).blockReason(BlockReason.ADMIN).build());
        final var anonymous = savedLink("anonymous", null);
        destinationCheckRepository.touch(checkedYesterday.getId(), checkedYesterday.getLongUrl(), TONIGHT.minusHours(20));
        destinationCheckRepository.touch(checkedTonight.getId(), checkedTonight.getLongUrl(), TONIGHT.plusHours(1));
        destinationCheckRepository.touch(anonymous.getId(), anonymous.getLongUrl(), TONIGHT.minusDays(2));
        jdbcTemplate.update("update url set active = false where id = ?", inactive.getId());

        final var due = destinationCheckRepository.findDue(TONIGHT, 10, java.util.List.of());

        assertThat(due).extracting(DestinationCheckRepository.DueLink::id)
                .containsExactly(neverChecked.getId(), anonymous.getId(), checkedYesterday.getId())
                .doesNotContain(checkedTonight.getId(), inactive.getId(), blocked.getId());
        assertThat(due.getFirst()).isEqualTo(new DestinationCheckRepository.DueLink(
                neverChecked.getId(), "https://example.org/never", 0, null, ana.getId(), "never"));
        assertThat(due.get(1).ownerId()).isNull();
        assertThat(destinationCheckRepository.findDue(TONIGHT, 1, java.util.List.of())).extracting(DestinationCheckRepository.DueLink::id)
                .containsExactly(neverChecked.getId());
    }

    @Test
    void findDueLeavesOutTheIdsAlreadyTriedThisRun() {
        final var ana = savedUser("ana@example.com");
        final var first = savedLink("first", ana);
        final var second = savedLink("second", ana);
        final var third = savedLink("third", ana);
        final var excluded = new java.util.ArrayList<Long>(java.util.stream.LongStream.rangeClosed(1_000_000, 1_002_500).boxed().toList());
        excluded.add(first.getId());
        excluded.add(third.getId());

        assertThat(destinationCheckRepository.findDue(TONIGHT, 10, excluded)).extracting(DestinationCheckRepository.DueLink::id)
                .containsExactly(second.getId());
        assertThat(destinationCheckRepository.findDue(TONIGHT, 10, java.util.List.of())).extracting(DestinationCheckRepository.DueLink::id)
                .containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void recordIsGuardedByTheCheckedUrl() {
        final var link = savedLink("menu", savedUser("ana@example.com"));
        jdbcTemplate.update("update url set long_url = ? where id = ?", "https://new.example", link.getId());

        assertThat(destinationCheckRepository.record(link.getId(), "https://example.org/menu", NOW, 3, NOW, "404")).isFalse();
        assertThat(destinationCheckRepository.touch(link.getId(), "https://example.org/menu", NOW)).isFalse();
        assertThat(jdbcTemplate.queryForMap("select destination_checked_at, destination_fail_streak, destination_dead_since from url where id = ?", link.getId()))
                .containsEntry("DESTINATION_CHECKED_AT", null)
                .containsEntry("DESTINATION_FAIL_STREAK", 0)
                .containsEntry("DESTINATION_DEAD_SINCE", null);
        assertThat(destinationCheckRepository.touch(link.getId(), "https://new.example", NOW)).isTrue();
    }

    @Test
    void resetClearsEveryColumn() {
        final var link = savedLink("menu", savedUser("ana@example.com"));
        destinationCheckRepository.record(link.getId(), link.getLongUrl(), NOW, 3, NOW, "410");

        destinationCheckRepository.reset(link.getId());

        assertThat(jdbcTemplate.queryForMap(
                "select destination_checked_at, destination_fail_streak, destination_dead_since, destination_last_status from url where id = ?", link.getId()))
                .containsEntry("DESTINATION_CHECKED_AT", null)
                .containsEntry("DESTINATION_FAIL_STREAK", 0)
                .containsEntry("DESTINATION_DEAD_SINCE", null)
                .containsEntry("DESTINATION_LAST_STATUS", null);
    }

    @Test
    void countDeadSplitsAnonymous() {
        final var ana = savedUser("ana@example.com");
        final var deadOwned = savedLink("owned", ana);
        final var deadAnonymous = savedLink("anon", null);
        final var deadInactive = savedLink("inactive", ana);
        savedLink("alive", ana);
        List.of(deadOwned, deadAnonymous, deadInactive)
                .forEach(link -> destinationCheckRepository.record(link.getId(), link.getLongUrl(), NOW, 3, NOW, "404"));
        jdbcTemplate.update("update url set active = false where id = ?", deadInactive.getId());

        assertThat(destinationCheckRepository.countDead(null)).isEqualTo(2);
        assertThat(destinationCheckRepository.countDead(true)).isEqualTo(1);
        assertThat(destinationCheckRepository.countDead(false)).isEqualTo(1);
    }

    @Test
    void consentingUserIdsSeeOnlyActiveEmailRows() {
        final var active = savedUser("active@example.com");
        final var revoked = savedUser("revoked@example.com");
        final var channelOnly = savedUser("channel@example.com");
        final var otherType = savedUser("other@example.com");
        notificationConsentRepository.saveAndFlush(emailConsent(active, NotificationType.WEEKLY_SUMMARY_EMAIL).build());
        notificationConsentRepository.saveAndFlush(emailConsent(revoked, NotificationType.WEEKLY_SUMMARY_EMAIL)
                .revokedAt(NOW).revokedSource(ConsentSource.UNSUBSCRIBE_LINK).build());
        notificationConsentRepository.saveAndFlush(channelConsent(channelOnly, savedChannel(channelOnly, "Mine"), NotificationType.WEEKLY_SUMMARY).build());
        notificationConsentRepository.saveAndFlush(emailConsent(otherType, NotificationType.DEAD_LINK_EMAIL).build());
        final var everyone = List.of(active.getId(), revoked.getId(), channelOnly.getId(), otherType.getId());

        assertThat(notificationConsentRepository.findConsentingUserIds(NotificationType.WEEKLY_SUMMARY_EMAIL, everyone))
                .containsExactly(active.getId());
        assertThat(notificationConsentRepository.findAllConsentingUserIds(NotificationType.WEEKLY_SUMMARY_EMAIL))
                .containsExactly(active.getId());
        assertThat(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(revoked.getId(),
                NotificationType.WEEKLY_SUMMARY_EMAIL)).isEmpty();
        assertThat(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(active.getId(),
                NotificationType.WEEKLY_SUMMARY_EMAIL)).isPresent();
    }

    @Test
    void enabledChannelsWithAnEventAreFoundByActiveConsent() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var anasChannel = savedChannel(ana, "Ana's");
        final var anasOffChannel = notificationChannelRepository.saveAndFlush(channel(ana, "Off").enabled(false).build());
        final var bobsRevoked = savedChannel(bob, "Bob's");
        notificationConsentRepository.saveAndFlush(channelConsent(ana, anasChannel, NotificationType.LINK_DEAD).build());
        notificationConsentRepository.saveAndFlush(channelConsent(ana, anasOffChannel, NotificationType.LINK_DEAD).build());
        notificationConsentRepository.saveAndFlush(channelConsent(bob, bobsRevoked, NotificationType.LINK_DEAD)
                .revokedAt(NOW).revokedSource(ConsentSource.SETTINGS).build());
        entityManager.clear();

        assertThat(notificationChannelRepository.findEnabledWithEvent(NotificationType.LINK_DEAD, List.of(ana.getId(), bob.getId())))
                .extracting(NotificationChannel::getId)
                .containsExactly(anasChannel.getId());
        assertThat(notificationChannelRepository.findOwnerIdsWithEnabledEvent(NotificationType.LINK_DEAD)).containsExactly(ana.getId());
        assertThat(notificationChannelRepository.findOwnerIdsWithEnabledEvent(NotificationType.LINK_BACK)).isEmpty();
    }

    @Test
    void sealedValuesAreLeftOutOfToString() {
        final var channel = channel(User.builder().id(1L).build(), "Team alerts")
                .targetSealed("v1:target").accessTokenSealed("v1:token").signingSecretSealed("v1:secret").build();

        assertThat(channel.toString()).doesNotContain("v1:target", "v1:token", "v1:secret");
    }

    private long rows(final String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void aDeactivatedOwnersEmailConsentCountsAgainOnlyOnceTheAccountIsActive() {
        final var active = savedUser("ana@example.com");
        final var deactivated = savedUser("bob@example.com");
        final var type = NotificationType.EXPIRY_REMINDER_EMAIL;
        notificationConsentRepository.saveAndFlush(emailConsent(active, type).build());
        notificationConsentRepository.saveAndFlush(emailConsent(deactivated, type).build());
        jdbcTemplate.update("update user_account set active = false where id = ?", deactivated.getId());
        entityManager.clear();
        final var everyone = List.of(active.getId(), deactivated.getId());

        assertThat(emailConsentReader.hasActiveEmailConsent(active.getId(), type)).isTrue();
        assertThat(emailConsentReader.hasActiveEmailConsent(deactivated.getId(), type)).isFalse();
        assertThat(emailConsentReader.consenters(type, everyone)).containsExactly(active.getId());
        assertThat(emailConsentReader.allConsenters(type)).containsExactly(active.getId());
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification_consent where user_id = ? and revoked_at is null",
                Long.class, deactivated.getId())).isOne();

        jdbcTemplate.update("update user_account set active = true where id = ?", deactivated.getId());

        assertThat(emailConsentReader.hasActiveEmailConsent(deactivated.getId(), type)).isTrue();
        assertThat(emailConsentReader.consenters(type, everyone)).containsExactlyInAnyOrder(active.getId(), deactivated.getId());
        assertThat(emailConsentReader.allConsenters(type)).containsExactlyInAnyOrder(active.getId(), deactivated.getId());
    }

    @Test
    void theEmailConsentReaderSeesNeitherRevokedNorChannelRowsAndAsksNothingForNobody() {
        final var ana = savedUser("ana@example.com");
        final var type = NotificationType.WEEKLY_SUMMARY_EMAIL;
        notificationConsentRepository.saveAndFlush(emailConsent(ana, type).revokedAt(NOW).revokedSource(ConsentSource.SETTINGS).build());
        notificationConsentRepository.saveAndFlush(channelConsent(ana, savedChannel(ana, "Team"), type).build());

        assertThat(emailConsentReader.hasActiveEmailConsent(ana.getId(), type)).isFalse();
        assertThat(emailConsentReader.consenters(type, List.of(ana.getId()))).isEmpty();
        assertThat(emailConsentReader.allConsenters(type)).isEmpty();
        assertThat(emailConsentReader.consenters(type, List.of())).isEmpty();
    }

    private User savedUser(final String email) {
        return userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
    }

    private Url savedLink(final String shortUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).owner(owner).build());
    }

    private NotificationChannel savedChannel(final User owner, final String name) {
        return notificationChannelRepository.saveAndFlush(channel(owner, name).build());
    }

    private static NotificationChannel.NotificationChannelBuilder channel(final User owner, final String name) {
        return NotificationChannel.builder()
                .owner(owner)
                .name(name)
                .type(ChannelType.DISCORD)
                .enabled(true)
                .targetSealed("v1:sealed")
                .maskedTarget("https://discord.com/••••/abc");
    }

    private static NotificationConsent.NotificationConsentBuilder emailConsent(final User owner, final NotificationType type) {
        return NotificationConsent.builder().owner(owner).type(type).grantedAt(NOW).source(ConsentSource.SETTINGS);
    }

    private static NotificationConsent.NotificationConsentBuilder channelConsent(final User owner, final NotificationChannel channel,
                                                                                 final NotificationType type) {
        return emailConsent(owner, type).channelId(channel.getId()).channelLabel("Discord: " + channel.getName());
    }

    private static NotificationDelivery.NotificationDeliveryBuilder delivery(final NotificationChannel channel, final String dedupeKey) {
        return NotificationDelivery.builder()
                .uid(UUID.randomUUID().toString())
                .channelId(channel.getId())
                .event(NotificationType.LINK_DEAD)
                .dedupeKey(dedupeKey)
                .body("{}")
                .message("hrva.cc: 1 link looks broken")
                .status(DeliveryStatus.PENDING)
                .nextAttemptAt(NOW)
                .createdAt(NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = NotificationChannel.class)
    @Import({NotificationClaimRepository.class, DestinationCheckRepository.class, EmailConsentReader.class})
    @EnableJpaRepositories(
            basePackageClasses = NotificationChannelRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            NotificationChannelRepository.class,
                            NotificationConsentRepository.class,
                            NotificationDeliveryRepository.class,
                            UrlRepository.class,
                            UserRepository.class}))
    static class TestApplication {
    }

}
