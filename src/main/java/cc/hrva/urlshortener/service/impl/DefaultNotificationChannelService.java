package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.notification.ChannelTestResponse;
import cc.hrva.urlshortener.dto.notification.LastDelivery;
import cc.hrva.urlshortener.dto.notification.NotificationChannelRequest;
import cc.hrva.urlshortener.dto.notification.NotificationChannelResponse;
import cc.hrva.urlshortener.dto.notification.SigningSecretResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ChannelValidationException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.NotificationChannelNotFoundException;
import cc.hrva.urlshortener.exception.NotificationTestUnavailableException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.service.NotificationChannelService;
import cc.hrva.urlshortener.service.NotificationConsentService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.ChannelTargets;
import cc.hrva.urlshortener.service.notification.NotificationErrors;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import cc.hrva.urlshortener.service.notification.SecretBox;
import cc.hrva.urlshortener.service.notification.channel.ChannelSenders;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultNotificationChannelService implements NotificationChannelService {

    public static final int MOST_CHANNELS = 10;
    public static final String TEST_LIMIT_NAME = "notify-test";
    public static final int TESTS_PER_WINDOW = 10;
    public static final Duration TEST_WINDOW = Duration.ofMinutes(10);
    public static final String TOO_MANY_TESTS = "You've sent a lot of tests in a short time. Try again in a few minutes.";
    public static final String TESTS_UNAVAILABLE = "Sending a test isn't available right now. Try again in a minute.";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    static final String NOT_FOUND = "Notification channel not found";
    static final String TOO_MANY_CHANNELS = "You can have up to 10 channels. Remove one to add another.";
    static final String TYPE_CHANGED = "A channel's type can't be changed. Add a new channel instead.";
    static final String NOT_CHANNEL_EVENTS = "Events must be LINK_DEAD, LINK_BACK, DAILY_SUMMARY or WEEKLY_SUMMARY";
    static final String NO_SIGNING_SECRET = "Only webhook channels have a signing secret.";

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final int SIGNING_SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationConsentRepository notificationConsentRepository;
    private final NotificationConsentService notificationConsentService;
    private final UserService userService;
    private final AccountLock accountLock;
    private final RateLimiter rateLimiter;
    private final SecretBox secretBox;
    private final ChannelTargets channelTargets;
    private final ChannelSenders channelSenders;
    private final NotificationMessages notificationMessages;
    private final NotificationPayloads notificationPayloads;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Override
    public List<NotificationChannelResponse> mine() {
        final var user = currentUser();
        final var channels = notificationChannelRepository.findByOwnerIdOrderByCreatedAtAscIdAsc(user.getId());
        final var events = eventsOf(channels.stream().map(NotificationChannel::getId).toList());
        return channels.stream()
                .map(channel -> response(channel, events.getOrDefault(channel.getId(), EnumSet.noneOf(NotificationType.class)), null))
                .toList();
    }

    @Override
    @Transactional
    public NotificationChannelResponse create(final NotificationChannelRequest request) {
        final var user = currentUser();
        final var events = channelEventsOf(request.getEvents());
        requireWithinSettingsLimit(user.getId());
        requireValid(request.getType(), request, true);

        final var owner = accountLock.lock(user);
        if (notificationChannelRepository.countByOwnerId(owner.getId()) >= MOST_CHANNELS) {
            throw new ApiException(TOO_MANY_CHANNELS);
        }

        final var context = SecretBox.contextFor(owner.getId());
        final var type = request.getType();
        final var target = ChannelTargets.normalized(type, type == ChannelType.TELEGRAM ? request.getBotToken().strip() : request.getTarget().strip());
        final var signingSecret = type == ChannelType.WEBHOOK ? newSecret() : null;
        final var channel = notificationChannelRepository.save(NotificationChannel.builder()
                .owner(owner)
                .name(request.getName().strip())
                .type(type)
                .enabled(request.getEnabled() == null || request.getEnabled())
                .targetSealed(secretBox.seal(target, context))
                .accessTokenSealed(type == ChannelType.NTFY && StringUtils.isNotBlank(request.getAccessToken())
                        ? secretBox.seal(request.getAccessToken().strip(), context)
                        : null)
                .signingSecretSealed(signingSecret == null ? null : secretBox.seal(signingSecret, context))
                .chatId(type == ChannelType.TELEGRAM ? request.getChatId().strip() : null)
                .maskedTarget(ChannelTargets.mask(type, target))
                .build());
        notificationConsentService.grantChannelEvents(owner, channel, events);
        log.info("Notification channel added user id={} channel id={} type={}", owner.getId(), channel.getId(), type);
        return response(channel, events, signingSecret);
    }

    @Override
    @Transactional
    public NotificationChannelResponse update(final long id, final NotificationChannelRequest request) {
        final var user = currentUser();
        final var events = channelEventsOf(request.getEvents());
        requireWithinSettingsLimit(user.getId());
        final var channel = owned(user, id);
        if (request.getType() != channel.getType()) {
            throw new ApiException(TYPE_CHANGED);
        }
        requireValid(channel.getType(), request, false);
        accountLock.lock(user);

        channel.setName(request.getName().strip());
        if (request.getEnabled() != null) {
            switchTo(channel, request.getEnabled());
        }
        replaceGivenSecrets(channel, request);
        channel.setUpdatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        notificationChannelRepository.saveAndFlush(channel);

        final var current = notificationConsentService.channelEvents(id);
        final var granted = EnumSet.noneOf(NotificationType.class);
        granted.addAll(events);
        granted.removeAll(current);
        final var revoked = EnumSet.noneOf(NotificationType.class);
        revoked.addAll(current);
        revoked.removeAll(events);
        if (!granted.isEmpty()) {
            notificationConsentService.grantChannelEvents(channel.getOwner(), channel, granted);
        }
        if (!revoked.isEmpty()) {
            notificationConsentService.revokeChannelEvents(id, revoked, ConsentSource.SETTINGS);
        }
        log.info("Notification channel changed user id={} channel id={}", user.getId(), id);
        return response(channel, events, null);
    }

    @Override
    @Transactional
    public void delete(final long id) {
        final var user = currentUser();
        requireWithinSettingsLimit(user.getId());
        final var channel = owned(user, id);

        notificationConsentService.revokeChannelEvents(id, EnumSet.copyOf(NotificationType.CHANNEL_EVENTS), ConsentSource.CHANNEL_REMOVED);
        notificationChannelRepository.delete(channel);
        log.info("Notification channel deleted user id={} channel id={}", user.getId(), id);
    }

    @Override
    @Transactional
    public SigningSecretResponse newSigningSecret(final long id) {
        final var user = currentUser();
        final var channel = owned(user, id);
        if (channel.getType() != ChannelType.WEBHOOK) {
            throw new ApiException(NO_SIGNING_SECRET);
        }
        requireWithinTestLimit(user.getId());

        final var signingSecret = newSecret();
        channel.setSigningSecretSealed(secretBox.seal(signingSecret, SecretBox.contextFor(user.getId())));
        channel.setUpdatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        notificationChannelRepository.save(channel);
        log.info("Notification channel signing secret replaced user id={} channel id={}", user.getId(), id);
        return new SigningSecretResponse(signingSecret);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChannelTestResponse test(final long id) {
        final var user = currentUser();
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        final var prepared = readOnly.execute(status -> {
            final var channel = owned(user, id);
            return new PreparedTest(channel, openTarget(channel));
        });
        requireWithinTestLimit(user.getId());

        final var uid = "test-" + UUID.randomUUID();
        final var json = notificationPayloads.json(NotificationPayloads.TEST_EVENT, uid, clock.instant(), notificationPayloads.test());
        final var result = channelSenders.send(prepared.channel(), prepared.target(), NotificationPayloads.TEST_EVENT, uid, json,
                notificationMessages.test(prepared.channel()));
        final var error = NotificationErrors.describe(result, prepared.channel().getType());
        final var httpStatus = NotificationErrors.httpStatus(result);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> notificationChannelRepository.recordDelivery(id,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS), result.delivered() ? SENT : FAILED, httpStatus, error));
        log.info("Notification channel test user id={} channel id={} type={} delivered={} status={}", user.getId(), id,
                prepared.channel().getType(), result.delivered(), httpStatus);
        return new ChannelTestResponse(result.delivered(), httpStatus, error);
    }

    @Override
    public NotificationChannel loadOwned(final long id) {
        return owned(currentUser(), id);
    }

    @Override
    public ChannelTarget openTarget(final NotificationChannel channel) {
        final var context = SecretBox.contextFor(channel.getOwner().getId());
        return new ChannelTarget(
                secretBox.open(channel.getTargetSealed(), context),
                channel.getAccessTokenSealed() == null ? null : secretBox.open(channel.getAccessTokenSealed(), context),
                channel.getChatId(),
                channel.getSigningSecretSealed() == null ? null : secretBox.open(channel.getSigningSecretSealed(), context));
    }

    private record PreparedTest(NotificationChannel channel, ChannelTarget target) {
    }

    private Map<Long, Set<NotificationType>> eventsOf(final Collection<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        return notificationConsentRepository.findByChannelIdInAndRevokedAtIsNull(channelIds).stream()
                .collect(Collectors.groupingBy(NotificationConsent::getChannelId,
                        Collectors.mapping(NotificationConsent::getType, Collectors.toCollection(() -> EnumSet.noneOf(NotificationType.class)))));
    }

    private void requireValid(final ChannelType type, final NotificationChannelRequest request, final boolean creating) {
        final var errors = channelTargets.fieldErrors(type, request, creating);
        if (!errors.isEmpty()) {
            throw new ChannelValidationException(errors);
        }
    }

    private void switchTo(final NotificationChannel channel, final boolean enabled) {
        if (enabled && !channel.isEnabled()) {
            channel.setAutoDisabledAt(null);
            channel.setFailedDays(0);
            channel.setLastFailedDay(null);
        }
        channel.setEnabled(enabled);
    }

    private void replaceGivenSecrets(final NotificationChannel channel, final NotificationChannelRequest request) {
        final var context = SecretBox.contextFor(channel.getOwner().getId());
        final var type = channel.getType();
        final var target = type == ChannelType.TELEGRAM ? request.getBotToken() : request.getTarget();
        if (StringUtils.isNotBlank(target)) {
            final var normalized = ChannelTargets.normalized(type, target.strip());
            channel.setTargetSealed(secretBox.seal(normalized, context));
            channel.setMaskedTarget(ChannelTargets.mask(type, normalized));
        }
        if (type == ChannelType.NTFY && StringUtils.isNotBlank(request.getAccessToken())) {
            channel.setAccessTokenSealed(secretBox.seal(request.getAccessToken().strip(), context));
        }
        if (type == ChannelType.TELEGRAM && StringUtils.isNotBlank(request.getChatId())) {
            channel.setChatId(request.getChatId().strip());
        }
    }

    private NotificationChannel owned(final User user, final long id) {
        return notificationChannelRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotificationChannelNotFoundException(NOT_FOUND));
    }

    private void requireWithinSettingsLimit(final long userId) {
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(DefaultNotificationConsentService.SETTINGS_LIMIT_NAME + ":user:" + userId,
                    DefaultNotificationConsentService.SETTINGS_CHANGES_PER_HOUR, DefaultNotificationConsentService.SETTINGS_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Notification settings rate limit unavailable, allowing the change");
            return;
        }
        if (!allowed) {
            log.info("Notification channel change refused by the rate limit user id={}", userId);
            throw new RateLimitExceededException(DefaultNotificationConsentService.TOO_MANY_CHANGES);
        }
    }

    private void requireWithinTestLimit(final long userId) {
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(TEST_LIMIT_NAME + ":user:" + userId, TESTS_PER_WINDOW, TEST_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Notification test rate limit unavailable, refusing the test user id={}", userId);
            throw new NotificationTestUnavailableException(TESTS_UNAVAILABLE);
        }
        if (!allowed) {
            log.info("Notification test refused by the rate limit user id={}", userId);
            throw new RateLimitExceededException(TOO_MANY_TESTS);
        }
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }
        return user;
    }

    private static Set<NotificationType> channelEventsOf(final Set<String> names) {
        final var events = EnumSet.noneOf(NotificationType.class);
        for (final var name : names == null ? Set.<String>of() : names) {
            final var event = EnumUtils.getEnum(NotificationType.class, name);
            if (event == null || event.isEmail()) {
                throw new ApiException(NOT_CHANNEL_EVENTS);
            }
            events.add(event);
        }
        return events;
    }

    private static String newSecret() {
        final var bytes = new byte[SIGNING_SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static NotificationChannelResponse response(final NotificationChannel channel, final Set<NotificationType> events,
            final String signingSecret) {
        return new NotificationChannelResponse(
                channel.getId(),
                channel.getName(),
                channel.getType(),
                channel.isEnabled(),
                new ArrayList<>(events),
                channel.getMaskedTarget(),
                channel.getChatId(),
                channel.getAccessTokenSealed() != null,
                channel.getAutoDisabledAt(),
                channel.getLastDeliveryAt() == null
                        ? null
                        : new LastDelivery(channel.getLastDeliveryStatus(), channel.getLastDeliveryAt(), channel.getLastDeliveryHttp(),
                                channel.getLastDeliveryError()),
                channel.getCreatedAt(),
                channel.getUpdatedAt(),
                signingSecret);
    }

}
