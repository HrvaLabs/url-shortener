package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.NotificationDelivery;
import cc.hrva.urlshortener.model.enums.DeliveryStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.NotificationDeliveryRepository;
import cc.hrva.urlshortener.service.NotificationChannelService;
import cc.hrva.urlshortener.service.NotificationDeliveryService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationErrors;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import cc.hrva.urlshortener.service.notification.channel.ChannelSenders;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultNotificationDeliveryService implements NotificationDeliveryService {

    public static final List<Duration> RETRY_AFTER = List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(60));
    public static final int MOST_ATTEMPTS = RETRY_AFTER.size() + 1;
    public static final int TURN_OFF_AFTER_FAILED_DAYS = 10;

    static final String NOT_SENT = "Not sent: the channel is off or no longer gets this event.";

    private static final int BATCH_SIZE = 20;
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);
    private static final int DELIVERIES_KEPT_DAYS = 30;
    private static final int CLAIMS_KEPT_DAYS = 60;
    private static final PostResult NOT_POSTED = new PostResult(0, "error");
    private static final PostResult SECRETS_UNREADABLE = new PostResult(0, PublicHttpFetcher.POST_INVALID);

    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationConsentRepository notificationConsentRepository;
    private final NotificationClaimRepository notificationClaimRepository;
    private final NotificationChannelService notificationChannelService;
    private final ChannelSenders channelSenders;
    private final NotificationPayloads notificationPayloads;
    private final JsonMapper jsonMapper;
    private final AnalyticsDays analyticsDays;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int enqueue(final NotificationType event, final String dedupeKey, final Collection<NotificationChannel> channels,
            final Map<Long, ChannelContent> contentByOwnerId) {
        if (channels.isEmpty()) {
            return 0;
        }
        final var channelIds = channels.stream().map(NotificationChannel::getId).toList();
        final var consented = readOnly().execute(status -> notificationConsentRepository.findByChannelIdInAndRevokedAtIsNull(channelIds).stream()
                .filter(consent -> consent.getType() == event)
                .map(NotificationConsent::getChannelId)
                .collect(Collectors.toSet()));
        final var createdAt = clock.instant();
        final var now = now();
        var created = 0;
        for (final var channel : channels) {
            final var content = contentByOwnerId.get(channel.getOwner().getId());
            if (!channel.isEnabled() || !Objects.requireNonNull(consented).contains(channel.getId()) || content == null) {
                continue;
            }
            final var uid = UUID.randomUUID().toString();
            final var delivery = NotificationDelivery.builder()
                    .uid(uid)
                    .channelId(channel.getId())
                    .event(event)
                    .dedupeKey(dedupeKey)
                    .body(notificationPayloads.json(event, uid, createdAt, content.data()))
                    .message(jsonMapper.writeValueAsString(content.message()))
                    .status(DeliveryStatus.PENDING)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build();
            try {
                writing().executeWithoutResult(status -> notificationDeliveryRepository.saveAndFlush(delivery));
                created++;
            } catch (final DataIntegrityViolationException exception) {
                log.debug("Notification delivery already queued channel id={} event={}", channel.getId(), event);
            }
        }
        log.info("Notification deliveries queued event={} created={} channels={}", event, created, channels.size());
        return created;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void drain() {
        if (!running.compareAndSet(false, true)) {
            log.info("Notification delivery drain skipped: the previous run is still going");
            return;
        }
        try {
            final var released = writing().execute(status -> notificationDeliveryRepository.releaseStale(now().minus(STALE_AFTER),
                    DeliveryStatus.PENDING, DeliveryStatus.SENDING));
            if (released != null && released > 0) {
                log.info("Notification deliveries released after an interrupted send count={}", released);
            }
            final var due = readOnly().execute(status -> notificationDeliveryRepository
                    .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(DeliveryStatus.PENDING, now()).stream()
                    .limit(BATCH_SIZE)
                    .map(Due::of)
                    .toList());
            Objects.requireNonNull(due).forEach(this::deliver);
        } finally {
            running.set(false);
        }
    }

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void drainInBackground() {
        drain();
    }

    @Override
    @Transactional
    public void deleteOld() {
        final var now = now();
        final var deliveries = notificationDeliveryRepository.deleteCreatedBefore(now.minusDays(DELIVERIES_KEPT_DAYS));
        final var claims = notificationClaimRepository.deleteCreatedBefore(now.minusDays(CLAIMS_KEPT_DAYS));
        log.info("Scheduled cleanup: old notification records deliveriesDeleted={} claimsDeleted={}", deliveries, claims);
    }

    private void deliver(final Due due) {
        final var claimed = writing().execute(status -> notificationDeliveryRepository.claim(due.id(), now(), DeliveryStatus.PENDING, DeliveryStatus.SENDING));
        if (claimed == null || claimed == 0) {
            return;
        }
        final var prepared = readOnly().execute(status -> prepare(due));
        if (prepared == null) {
            writing().executeWithoutResult(status -> notificationDeliveryRepository.giveUp(due.id(), due.attempts(), now(), null, NOT_SENT,
                    DeliveryStatus.GAVE_UP, DeliveryStatus.SENDING));
            log.info("Notification delivery not sent, the channel is off or no longer gets the event delivery id={} channel id={} event={}",
                    due.id(), due.channelId(), due.event());
            return;
        }
        record(due, prepared.channel(), send(due, prepared));
    }

    private Prepared prepare(final Due due) {
        final var channel = notificationChannelRepository.findById(due.channelId())
                .filter(NotificationChannel::isEnabled)
                .filter(found -> Boolean.TRUE.equals(found.getOwner().getActive()))
                .filter(found -> notificationConsentRepository.existsByChannelIdAndTypeAndRevokedAtIsNull(found.getId(), due.event()));
        if (channel.isEmpty()) {
            return null;
        }
        try {
            return new Prepared(channel.get(), notificationChannelService.openTarget(channel.get()));
        } catch (final RuntimeException exception) {
            log.warn("Notification channel secrets can't be opened channel id={} exception={}", due.channelId(), exception.getClass().getName());
            return new Prepared(channel.get(), null);
        }
    }

    private PostResult send(final Due due, final Prepared prepared) {
        if (prepared.target() == null) {
            return SECRETS_UNREADABLE;
        }
        try {
            final var message = jsonMapper.readValue(due.message(), NotificationMessage.class);
            return channelSenders.send(prepared.channel(), prepared.target(), due.event().wireName(), due.uid(), due.body(), message);
        } catch (final RuntimeException exception) {
            log.warn("Notification delivery can't be sent delivery id={} exception={}", due.id(), exception.getClass().getName());
            return NOT_POSTED;
        }
    }

    private void record(final Due due, final NotificationChannel channel, final PostResult result) {
        final var attempts = due.attempts() + 1;
        final var at = now();
        final var http = NotificationErrors.httpStatus(result);
        final var error = NotificationErrors.describe(result, channel.getType());
        final var channelId = channel.getId();
        writing().executeWithoutResult(status -> {
            if (result.delivered()) {
                notificationDeliveryRepository.markSent(due.id(), attempts, at, http, DeliveryStatus.SENT, DeliveryStatus.SENDING);
                notificationChannelRepository.recordDelivery(channelId, at, DefaultNotificationChannelService.SENT, http, null);
                notificationChannelRepository.resetFailures(channelId);
                return;
            }
            notificationChannelRepository.recordDelivery(channelId, at, DefaultNotificationChannelService.FAILED, http, error);
            if (retryable(result) && attempts < MOST_ATTEMPTS) {
                notificationDeliveryRepository.retryLater(due.id(), attempts, at.plus(RETRY_AFTER.get(attempts - 1)), http, error,
                        DeliveryStatus.PENDING, DeliveryStatus.SENDING);
                return;
            }
            notificationDeliveryRepository.giveUp(due.id(), attempts, at, http, error, DeliveryStatus.GAVE_UP, DeliveryStatus.SENDING);
            notificationChannelRepository.countFailedDay(channelId, analyticsDays.today());
            if (notificationChannelRepository.turnOffAfterFailedDays(channelId, at, TURN_OFF_AFTER_FAILED_DAYS) == 1) {
                log.warn("Notification channel turned off after failing on {} different days channel id={}", TURN_OFF_AFTER_FAILED_DAYS, channelId);
            }
        });
        log.debug("Notification delivery attempt delivery id={} channel id={} attempt={} delivered={} status={}", due.id(), channelId, attempts,
                result.delivered(), http);
    }

    private static boolean retryable(final PostResult result) {
        final var status = result.httpStatus();
        if (status == 0) {
            return "timeout".equals(result.failure()) || "error".equals(result.failure());
        }
        return status == 408 || status == 429 || status / 100 == 5;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private TransactionTemplate readOnly() {
        final var template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    private TransactionTemplate writing() {
        return new TransactionTemplate(transactionManager);
    }

    private record Due(Long id, String uid, Long channelId, NotificationType event, String body, String message, int attempts) {

        private static Due of(final NotificationDelivery delivery) {
            return new Due(delivery.getId(), delivery.getUid(), delivery.getChannelId(), delivery.getEvent(), delivery.getBody(),
                    delivery.getMessage(), delivery.getAttempts());
        }

    }

    private record Prepared(NotificationChannel channel, ChannelTarget target) {
    }

}
