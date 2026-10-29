package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.dto.DataExportDto.ChannelExport;
import cc.hrva.urlshortener.dto.DataExportDto.ConsentExport;
import cc.hrva.urlshortener.dto.DataExportDto.NotificationExport;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationAccountData {

    private final NotificationConsentRepository notificationConsentRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationClaimRepository notificationClaimRepository;

    public NotificationExport exportFor(final User user) {
        final var consents = notificationConsentRepository.findByOwnerIdOrderByGrantedAtAscIdAsc(user.getId());
        final var eventsByChannel = consents.stream()
                .filter(consent -> consent.getChannelId() != null && consent.getRevokedAt() == null)
                .collect(Collectors.groupingBy(NotificationConsent::getChannelId,
                        Collectors.mapping(NotificationConsent::getType, Collectors.toList())));

        return new NotificationExport(
                consents.stream().map(NotificationAccountData::consentExport).toList(),
                notificationChannelRepository.findByOwnerIdOrderByCreatedAtAscIdAsc(user.getId()).stream()
                        .map(channel -> channelExport(channel, eventsByChannel))
                        .toList());
    }

    public void deleteFor(final User user) {
        final var claims = notificationClaimRepository.deleteByUserId(user.getId());
        final var consents = notificationConsentRepository.deleteByOwnerId(user.getId());
        final var channels = notificationChannelRepository.deleteByOwnerId(user.getId());
        log.info("Notification data deleted with its account user id={} channels={} consents={} claims={}", user.getId(), channels,
                consents, claims);
    }

    private static ConsentExport consentExport(final NotificationConsent consent) {
        return new ConsentExport(
                consent.getType(),
                consent.getChannelLabel(),
                consent.getGrantedAt(),
                consent.getSource(),
                consent.getRevokedAt(),
                consent.getRevokedSource());
    }

    private static ChannelExport channelExport(final NotificationChannel channel, final Map<Long, List<NotificationType>> eventsByChannel) {
        return new ChannelExport(
                channel.getName(),
                channel.getType(),
                channel.isEnabled(),
                eventsByChannel.getOrDefault(channel.getId(), List.of()).stream()
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList(),
                channel.getMaskedTarget(),
                channel.getCreatedAt(),
                channel.getLastDeliveryAt(),
                channel.getLastDeliveryStatus());
    }

}
