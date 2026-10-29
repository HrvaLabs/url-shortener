package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.DeadLinkNotificationService;
import cc.hrva.urlshortener.service.NotificationDeliveryService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import com.google.common.collect.Iterables;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultDeadLinkNotificationService implements DeadLinkNotificationService {

    private static final String DASHBOARD = "/dashboard";
    private static final String LINK_DEAD_KEY = "link.dead:";
    private static final String LINK_BACK_KEY = "link.back:";
    private static final String DEAD_EMAIL_CLAIM = "dead-email:";
    private static final int IDS_PER_QUERY = 1000;

    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final EmailConsentReader emailConsentReader;
    private final SendingEmailService sendingEmailService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final NotificationMessages notificationMessages;
    private final NotificationPayloads notificationPayloads;
    private final NotificationClaimRepository notificationClaimRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void notify(final DestinationCheckOutcome outcome) {
        final var newlyDead = owned(outcome.newlyDead());
        final var recovered = owned(outcome.recovered());
        if (newlyDead.isEmpty() && recovered.isEmpty()) {
            return;
        }

        final var recipients = Objects.requireNonNull(readOnly().execute(status -> recipients(newlyDead, recovered)));
        final var deadByOwner = byOwner(newlyDead, recipients.stillDeadIds());
        final var backByOwner = byOwner(recovered, recipients.recoveredIds());

        final var night = outcome.night();
        final var emails = sendEmails(night, deadByOwner, recipients.consentingEmails());
        final var deadDeliveries = enqueue(NotificationType.LINK_DEAD, LINK_DEAD_KEY + night, recipients.linkDeadChannels(), deadByOwner,
                links -> new ChannelContent(notificationPayloads.linkDead(links), notificationMessages.linkDead(links)));
        final var backDeliveries = enqueue(NotificationType.LINK_BACK, LINK_BACK_KEY + night, recipients.linkBackChannels(), backByOwner,
                links -> new ChannelContent(notificationPayloads.linkBack(links, night), notificationMessages.linkBack(links)));
        log.info("Dead link notices night={} newlyDead={} recovered={} emails={} linkDeadDeliveries={} linkBackDeliveries={}",
                night, newlyDead.size(), recovered.size(), emails, deadDeliveries, backDeliveries);
    }

    private Recipients recipients(final List<DeadLink> newlyDead, final List<DeadLink> recovered) {
        final var stillDeadIds = Set.copyOf(inChunks(urlIds(newlyDead), urlRepository::findActiveStillDeadIds));
        final var recoveredIds = Set.copyOf(inChunks(urlIds(recovered), urlRepository::findExistingIds));
        final var deadOwners = owners(newlyDead, stillDeadIds);
        final var backOwners = owners(recovered, recoveredIds);
        final var consenters = inChunks(deadOwners, chunk -> emailConsentReader.consenters(NotificationType.DEAD_LINK_EMAIL, chunk));
        final Map<Long, String> consentingEmails = inChunks(consenters, userRepository::findAllById).stream()
                .filter(user -> user.getEmail() != null)
                .collect(Collectors.toUnmodifiableMap(User::getId, User::getEmail));
        return new Recipients(stillDeadIds, recoveredIds, consentingEmails, channels(NotificationType.LINK_DEAD, deadOwners),
                channels(NotificationType.LINK_BACK, backOwners));
    }

    private List<NotificationChannel> channels(final NotificationType event, final Set<Long> ownerIds) {
        return inChunks(ownerIds, chunk -> notificationChannelRepository.findEnabledWithEvent(event, chunk)).stream()
                .sorted(Comparator.comparingLong(NotificationChannel::getId))
                .toList();
    }

    private static <T> List<T> inChunks(final Collection<Long> ids, final Function<List<Long>, ? extends Collection<T>> query) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return StreamSupport.stream(Iterables.partition(List.copyOf(ids), IDS_PER_QUERY).spliterator(), false)
                .flatMap(chunk -> query.apply(chunk).stream())
                .toList();
    }

    private static Set<Long> urlIds(final List<DeadLink> links) {
        return links.stream().map(DeadLink::urlId).collect(Collectors.toUnmodifiableSet());
    }

    private int sendEmails(final LocalDate night, final Map<Long, List<DeadLink>> deadByOwner, final Map<Long, String> consentingEmails) {
        final var dashboard = notificationMessages.frontendPage(DASHBOARD);
        final var now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        var queued = 0;
        for (final var entry : deadByOwner.entrySet()) {
            final var email = consentingEmails.get(entry.getKey());
            if (email == null) {
                continue;
            }
            try {
                if (!notificationClaimRepository.claim(entry.getKey(), DEAD_EMAIL_CLAIM + night, now)) {
                    log.info("Dead link email already claimed owner id={} night={}", entry.getKey(), night);
                    continue;
                }
                sendingEmailService.sendDeadLinksEmail(new DeadLinksEmail(entry.getKey(), email, entry.getValue(), dashboard));
                queued++;
            } catch (final RuntimeException exception) {
                log.warn("Dead link email couldn't be queued owner id={} exception={}", entry.getKey(), exception.getClass().getName());
            }
        }
        return queued;
    }

    private int enqueue(final NotificationType event, final String dedupeKey, final List<NotificationChannel> channels,
            final Map<Long, List<DeadLink>> linksByOwner, final Function<List<DeadLink>, ChannelContent> content) {
        if (channels.isEmpty()) {
            return 0;
        }
        final var contentByOwnerId = channels.stream()
                .map(channel -> channel.getOwner().getId())
                .distinct()
                .filter(linksByOwner::containsKey)
                .collect(Collectors.toUnmodifiableMap(Function.identity(), ownerId -> content.apply(linksByOwner.get(ownerId))));
        try {
            return notificationDeliveryService.enqueue(event, dedupeKey, channels, contentByOwnerId);
        } catch (final RuntimeException exception) {
            log.warn("Dead link notices couldn't be queued event={} exception={}", event, exception.getClass().getName());
            return 0;
        }
    }

    private TransactionTemplate readOnly() {
        final var template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    private static List<DeadLink> owned(final List<DeadLink> links) {
        return links.stream().filter(link -> link.ownerId() != null).toList();
    }

    private static Set<Long> owners(final Collection<DeadLink> links, final Set<Long> existingIds) {
        return links.stream()
                .filter(link -> existingIds.contains(link.urlId()))
                .map(DeadLink::ownerId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<Long, List<DeadLink>> byOwner(final List<DeadLink> links, final Set<Long> existingIds) {
        return links.stream()
                .filter(link -> existingIds.contains(link.urlId()))
                .sorted(Comparator.comparingLong(DeadLink::urlId))
                .collect(Collectors.groupingBy(DeadLink::ownerId, TreeMap::new, Collectors.toList()));
    }

    private record Recipients(Set<Long> stillDeadIds, Set<Long> recoveredIds, Map<Long, String> consentingEmails,
                              List<NotificationChannel> linkDeadChannels, List<NotificationChannel> linkBackChannels) {
    }

}
