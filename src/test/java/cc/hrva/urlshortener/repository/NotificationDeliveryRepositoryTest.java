package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationDelivery;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.DeliveryStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = NotificationDeliveryRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:notify-delivery-repository;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class NotificationDeliveryRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 8, 0);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private NotificationChannel channel;

    @BeforeEach
    void setUp() {
        final var ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).build());
        channel = channelRepository.saveAndFlush(NotificationChannel.builder().owner(ana).name("Team alerts").type(ChannelType.DISCORD).enabled(true)
                .targetSealed("v1:x").maskedTarget("m").createdAt(NOW).updatedAt(NOW).build());
    }

    @Test
    void aDueRowIsClaimedOnce() {
        final var due = saved("key:1", DeliveryStatus.PENDING, NOW, null);

        assertThat(deliveryRepository.claim(due.getId(), NOW, DeliveryStatus.PENDING, DeliveryStatus.SENDING)).isEqualTo(1);
        assertThat(deliveryRepository.claim(due.getId(), NOW, DeliveryStatus.PENDING, DeliveryStatus.SENDING)).isZero();
        assertThat(reloaded(due)).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(DeliveryStatus.SENDING);
            assertThat(row.getClaimedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aRowThatIsNotDueYetIsNotClaimed() {
        final var later = saved("key:1", DeliveryStatus.PENDING, NOW.plusSeconds(1), null);

        assertThat(deliveryRepository.claim(later.getId(), NOW, DeliveryStatus.PENDING, DeliveryStatus.SENDING)).isZero();
        assertThat(deliveryRepository.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(DeliveryStatus.PENDING, NOW)).isEmpty();
    }

    @Test
    void onlySendingRowsClaimedBeforeTheCutoffAreReleased() {
        final var stale = saved("key:1", DeliveryStatus.SENDING, NOW, NOW.minusMinutes(11));
        final var fresh = saved("key:2", DeliveryStatus.SENDING, NOW, NOW.minusMinutes(9));

        assertThat(deliveryRepository.releaseStale(NOW.minusMinutes(10), DeliveryStatus.PENDING, DeliveryStatus.SENDING)).isEqualTo(1);
        assertThat(reloaded(stale).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(reloaded(stale).getClaimedAt()).isNull();
        assertThat(reloaded(fresh).getStatus()).isEqualTo(DeliveryStatus.SENDING);
    }

    @Test
    void outcomesAreWrittenOnlyToAClaimedRow() {
        final var pending = saved("key:1", DeliveryStatus.PENDING, NOW, null);
        final var sending = saved("key:2", DeliveryStatus.SENDING, NOW, NOW);

        assertThat(deliveryRepository.markSent(pending.getId(), 1, NOW, 204, DeliveryStatus.SENT, DeliveryStatus.SENDING)).isZero();
        assertThat(deliveryRepository.retryLater(sending.getId(), 1, NOW.plusMinutes(1), 503, "e", DeliveryStatus.PENDING, DeliveryStatus.SENDING))
                .isEqualTo(1);
        assertThat(reloaded(sending)).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
            assertThat(row.getClaimedAt()).isNull();
            assertThat(row.getLastHttp()).isEqualTo(503);
        });
        assertThat(deliveryRepository.giveUp(pending.getId(), 0, NOW, null, "x", DeliveryStatus.GAVE_UP, DeliveryStatus.SENDING)).isZero();
    }

    @Test
    void aFailedDayCountsOnceAndTheTenthTurnsTheChannelOff() {
        channelRepository.flush();
        assertThat(channelRepository.countFailedDay(channel.getId(), TODAY)).isEqualTo(1);
        assertThat(channelRepository.countFailedDay(channel.getId(), TODAY)).isZero();
        assertThat(channelRepository.turnOffAfterFailedDays(channel.getId(), NOW, 10)).isZero();
        entityManager.createQuery("update NotificationChannel ch set ch.failedDays = 10 where ch.id = :id").setParameter("id", channel.getId()).executeUpdate();

        assertThat(channelRepository.turnOffAfterFailedDays(channel.getId(), NOW, 10)).isEqualTo(1);
        assertThat(channelRepository.turnOffAfterFailedDays(channel.getId(), NOW, 10)).isZero();
        assertThat(channelRepository.findById(channel.getId())).hasValueSatisfying(row -> {
            assertThat(row.isEnabled()).isFalse();
            assertThat(row.getAutoDisabledAt()).isEqualTo(NOW);
            assertThat(row.getLastFailedDay()).isEqualTo(TODAY);
        });
        assertThat(channelRepository.resetFailures(channel.getId())).isEqualTo(1);
        assertThat(channelRepository.findById(channel.getId())).hasValueSatisfying(row -> {
            assertThat(row.getFailedDays()).isZero();
            assertThat(row.getLastFailedDay()).isNull();
        });
    }

    private NotificationDelivery saved(final String key, final DeliveryStatus status, final LocalDateTime nextAttemptAt, final LocalDateTime claimedAt) {
        return deliveryRepository.saveAndFlush(NotificationDelivery.builder()
                .uid(UUID.randomUUID().toString())
                .channelId(channel.getId())
                .event(NotificationType.LINK_DEAD)
                .dedupeKey(key)
                .body("{}")
                .message("{}")
                .status(status)
                .nextAttemptAt(nextAttemptAt)
                .claimedAt(claimedAt)
                .createdAt(NOW)
                .build());
    }

    private NotificationDelivery reloaded(final NotificationDelivery delivery) {
        entityManager.clear();
        return deliveryRepository.findById(delivery.getId()).orElseThrow();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = NotificationChannel.class)
    @EnableJpaRepositories(
            basePackageClasses = NotificationChannelRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {NotificationChannelRepository.class, NotificationDeliveryRepository.class, UserRepository.class}))
    static class TestApplication {
    }

}
