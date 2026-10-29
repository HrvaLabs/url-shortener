package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.notification.NotificationConsentResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.service.NotificationConsentService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationDescriptions;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultNotificationConsentService implements NotificationConsentService {

    public static final String SETTINGS_LIMIT_NAME = "notify-settings";
    public static final int SETTINGS_CHANGES_PER_HOUR = 60;
    public static final Duration SETTINGS_WINDOW = Duration.ofHours(1);
    public static final String TOO_MANY_CHANGES = "You've changed your notification settings a lot in a short time. Try again in a few minutes.";

    static final String NOT_AN_EMAIL_TYPE = "Notification type must be DEAD_LINK_EMAIL, WEEKLY_SUMMARY_EMAIL or EXPIRY_REMINDER_EMAIL";

    private static final String NOT_AUTHENTICATED = "Not authenticated";

    private final NotificationConsentRepository notificationConsentRepository;
    private final EmailConsentReader emailConsentReader;
    private final UserService userService;
    private final AccountLock accountLock;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    @Override
    public List<NotificationConsentResponse> mine() {
        final var user = currentUser();
        final var granted = notificationConsentRepository.findByOwnerIdAndChannelIdIsNullAndRevokedAtIsNull(user.getId()).stream()
                .collect(Collectors.toMap(NotificationConsent::getType, NotificationConsent::getGrantedAt, (first, second) -> first));

        return NotificationType.EMAIL_TYPES.stream().map(type -> response(type, granted.get(type))).toList();
    }

    @Override
    @Transactional
    public NotificationConsentResponse set(final String typeName, final boolean consented) {
        final var type = emailTypeOf(typeName);
        final var user = currentUser();
        requireWithinSettingsLimit(user.getId());

        final var owner = accountLock.lock(user);
        final var active = activeEmailConsent(owner.getId(), type);
        if (consented && active.isEmpty()) {
            final var granted = notificationConsentRepository.save(NotificationConsent.builder()
                    .owner(owner)
                    .type(type)
                    .grantedAt(now())
                    .source(ConsentSource.SETTINGS)
                    .build());
            log.info("Notification consent granted user id={} type={}", owner.getId(), type);
            return response(type, granted.getGrantedAt());
        }
        if (!consented && active.isPresent()) {
            revoke(active.get(), ConsentSource.SETTINGS);
            log.info("Notification consent revoked user id={} type={}", owner.getId(), type);
            return response(type, null);
        }

        return response(type, active.map(NotificationConsent::getGrantedAt).orElse(null));
    }

    @Override
    public boolean hasEmailConsent(final long userId, final NotificationType type) {
        return emailConsentReader.hasActiveEmailConsent(userId, type);
    }

    @Override
    public Set<Long> emailConsenters(final NotificationType type, final Collection<Long> userIds) {
        return emailConsentReader.consenters(type, userIds);
    }

    @Override
    @Transactional
    public boolean revokeByUnsubscribe(final long userId, final NotificationType type) {
        if (activeEmailConsent(userId, type).isEmpty()) {
            return false;
        }

        try {
            accountLock.lock(User.builder().id(userId).build());
        } catch (final NoAuthorizationException exception) {
            return false;
        }

        final var active = activeEmailConsent(userId, type);
        active.ifPresent(consent -> revoke(consent, ConsentSource.UNSUBSCRIBE_LINK));
        if (active.isPresent()) {
            log.info("Notification consent revoked by an unsubscribe link user id={} type={}", userId, type);
        }
        return active.isPresent();
    }

    @Override
    @Transactional
    public void grantChannelEvents(final User owner, final NotificationChannel channel, final Set<NotificationType> events) {
        requireChannelEvents(events);
        if (events.isEmpty()) {
            return;
        }

        final var locked = accountLock.lock(owner);
        final var active = channelEvents(channel.getId());
        final var label = NotificationDescriptions.channelLabel(channel.getType(), channel.getName());
        final var grantedAt = now();
        events.stream()
                .filter(event -> !active.contains(event))
                .sorted()
                .forEach(event -> notificationConsentRepository.save(NotificationConsent.builder()
                        .owner(locked)
                        .channelId(channel.getId())
                        .channelLabel(label)
                        .type(event)
                        .grantedAt(grantedAt)
                        .source(ConsentSource.SETTINGS)
                        .build()));
    }

    @Override
    @Transactional
    public void revokeChannelEvents(final long channelId, final Set<NotificationType> events, final ConsentSource source) {
        final var revoked = notificationConsentRepository.findByChannelIdAndRevokedAtIsNull(channelId).stream()
                .filter(consent -> events.contains(consent.getType()))
                .toList();
        if (revoked.isEmpty()) {
            return;
        }

        accountLock.lock(User.builder().id(revoked.getFirst().getOwner().getId()).build());
        notificationConsentRepository.findByChannelIdAndRevokedAtIsNull(channelId).stream()
                .filter(consent -> events.contains(consent.getType()))
                .forEach(consent -> revoke(consent, source));
    }

    @Override
    public Set<NotificationType> channelEvents(final long channelId) {
        final var events = EnumSet.noneOf(NotificationType.class);
        notificationConsentRepository.findByChannelIdAndRevokedAtIsNull(channelId).forEach(consent -> events.add(consent.getType()));
        return events;
    }

    private Optional<NotificationConsent> activeEmailConsent(final long userId, final NotificationType type) {
        return notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(userId, type);
    }

    private void revoke(final NotificationConsent consent, final ConsentSource source) {
        consent.setRevokedAt(now());
        consent.setRevokedSource(source);
        notificationConsentRepository.save(consent);
    }

    private void requireWithinSettingsLimit(final long userId) {
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(SETTINGS_LIMIT_NAME + ":user:" + userId, SETTINGS_CHANGES_PER_HOUR, SETTINGS_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Notification settings rate limit unavailable, allowing the change");
            return;
        }
        if (!allowed) {
            log.info("Notification settings change refused by the rate limit user id={}", userId);
            throw new RateLimitExceededException(TOO_MANY_CHANGES);
        }
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }

        return user;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static NotificationType emailTypeOf(final String typeName) {
        final var type = EnumUtils.getEnum(NotificationType.class, typeName);
        if (type == null || !type.isEmail()) {
            throw new ApiException(NOT_AN_EMAIL_TYPE);
        }

        return type;
    }

    private static void requireChannelEvents(final Set<NotificationType> events) {
        if (events.stream().anyMatch(NotificationType::isEmail)) {
            throw new IllegalArgumentException("Channel consent takes channel events only");
        }
    }

    private static NotificationConsentResponse response(final NotificationType type, final LocalDateTime grantedAt) {
        return new NotificationConsentResponse(type, grantedAt != null, grantedAt, NotificationDescriptions.of(type));
    }

}
