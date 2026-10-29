package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final EmailConsentReader emailConsentReader;
    private final SendingEmailService sendingEmailService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final NotificationMessages notificationMessages;
    private final NotificationPayloads notificationPayloads;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void notify(final DestinationCheckOutcome outcome) {
        final var newlyDead = owned(outcome.newlyDead());
        final var recovered = owned(outcome.recovered());
        if (newlyDead.isEmpty() && recovered.isEmpty()) {
            return;
        }

        final var recipients = Objects.requireNonNull(readOnly().execute(status -> recipients(newlyDead, recovered)));
        final var deadByOwner = byOwner(newlyDead, recipients.existingIds());
        final var backByOwner = byOwner(recovered, recipients.existingIds());

        final var emails = sendEmails(deadByOwner, recipients.consentingEmails());
        final var night = outcome.night();
        final var deadDeliveries = enqueue(NotificationType.LINK_DEAD, LINK_DEAD_KEY + night, recipients.linkDeadChannels(), deadByOwner,
                links -> new ChannelContent(notificationPayloads.linkDead(links), notificationMessages.linkDead(links)));
        final var backDeliveries = enqueue(NotificationType.LINK_BACK, LINK_BACK_KEY + night, recipients.linkBackChannels(), backByOwner,
                links -> new ChannelContent(notificationPayloads.linkBack(links, night), notificationMessages.linkBack(links)));
        log.info("Dead link notices night={} newlyDead={} recovered={} emails={} linkDeadDeliveries={} linkBackDeliveries={}",
                night, newlyDead.size(), recovered.size(), emails, deadDeliveries, backDeliveries);
    }

    private Recipients recipients(final List<DeadLink> newlyDead, final List<DeadLink> recovered) {
        final var ids = Stream.concat(newlyDead.stream(), recovered.stream()).map(DeadLink::urlId).collect(Collectors.toSet());
        final var existingIds = Set.copyOf(urlRepository.findExistingIds(ids));
        final var deadOwners = owners(newlyDead, existingIds);
        final var backOwners = owners(recovered, existingIds);
        final var consenters = emailConsentReader.consenters(NotificationType.DEAD_LINK_EMAIL, deadOwners);
        final Map<Long, String> consentingEmails = consenters.isEmpty() ? Map.of() : userRepository.findAllById(consenters).stream()
                .filter(user -> user.getEmail() != null)
                .collect(Collectors.toUnmodifiableMap(User::getId, User::getEmail));
        return new Recipients(existingIds, consentingEmails, channels(NotificationType.LINK_DEAD, deadOwners),
                channels(NotificationType.LINK_BACK, backOwners));
    }

    private List<NotificationChannel> channels(final NotificationType event, final Set<Long> ownerIds) {
        return ownerIds.isEmpty() ? List.of() : notificationChannelRepository.findEnabledWithEvent(event, ownerIds);
    }

    private int sendEmails(final Map<Long, List<DeadLink>> deadByOwner, final Map<Long, String> consentingEmails) {
        final var dashboard = notificationMessages.frontendPage(DASHBOARD);
        var queued = 0;
        for (final var entry : deadByOwner.entrySet()) {
            final var email = consentingEmails.get(entry.getKey());
            if (email == null) {
                continue;
            }
            try {
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

    private record Recipients(Set<Long> existingIds, Map<Long, String> consentingEmails, List<NotificationChannel> linkDeadChannels,
                              List<NotificationChannel> linkBackChannels) {
    }

}
