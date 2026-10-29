package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.LinkDayCounts;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.dto.notification.DailySummary.SummaryLink;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ClickSummaryService;
import cc.hrva.urlshortener.service.NotificationDeliveryService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultClickSummaryService implements ClickSummaryService {

    private static final String DAILY_KEY = "summary.daily:";

    private final NotificationChannelRepository notificationChannelRepository;
    private final LinkStatsRepository linkStatsRepository;
    private final UrlRepository urlRepository;
    private final NotificationDeliveryService notificationDeliveryService;
    private final NotificationMessages notificationMessages;
    private final NotificationPayloads notificationPayloads;
    private final AnalyticsDays analyticsDays;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendDaily() {
        final var day = analyticsDays.today().minusDays(1);
        final var channels = Objects.requireNonNull(readOnly().execute(status -> channelsWith(NotificationType.DAILY_SUMMARY)));
        final var ownerIds = channels.stream().map(channel -> channel.getOwner().getId()).collect(Collectors.toCollection(TreeSet::new));
        final var contentByOwnerId = new LinkedHashMap<Long, ChannelContent>();
        for (final var ownerId : ownerIds) {
            try {
                final var summary = readOnly().execute(status -> daily(ownerId, day));
                if (summary != null) {
                    contentByOwnerId.put(ownerId, new ChannelContent(notificationPayloads.daily(summary), notificationMessages.daily(summary)));
                }
            } catch (final RuntimeException exception) {
                log.warn("Daily click summary couldn't be collected owner id={} exception={}", ownerId, exception.getClass().getName());
            }
        }
        final var queued = contentByOwnerId.isEmpty() ? 0 : enqueue(NotificationType.DAILY_SUMMARY, DAILY_KEY + day, channels, contentByOwnerId);
        log.info("Daily click summaries day={} owners={} withClicks={} deliveries={}", day, ownerIds.size(), contentByOwnerId.size(), queued);
    }

    @Async
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendDailyInBackground() {
        sendDaily();
    }

    private List<NotificationChannel> channelsWith(final NotificationType event) {
        final var ownerIds = notificationChannelRepository.findOwnerIdsWithEnabledEvent(event);
        return ownerIds.isEmpty() ? List.of() : notificationChannelRepository.findEnabledWithEvent(event, ownerIds);
    }

    private DailySummary daily(final long ownerId, final LocalDate day) {
        final var totals = linkStatsRepository.findOwnerDaily(ownerId, day, day);
        final var clicks = totals.stream().mapToLong(AnalyticsDay::clicks).sum();
        final var botClicks = totals.stream().mapToLong(AnalyticsDay::botClicks).sum();
        if (clicks + botClicks == 0) {
            return null;
        }
        final var uniqueVisitors = totals.stream().mapToLong(AnalyticsDay::uniqueVisitors).sum();
        final var counts = linkStatsRepository.findOwnerLinkDay(ownerId, day, NotificationMessages.MOST_LINKS);
        final var links = linksById(counts.stream().map(LinkDayCounts::urlId).toList());
        return new DailySummary(day, clicks, uniqueVisitors, botClicks, counts.stream()
                .filter(count -> links.containsKey(count.urlId()))
                .map(count -> summaryLink(links.get(count.urlId()), count))
                .toList());
    }

    private Map<Long, Url> linksById(final List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return urlRepository.findAllById(ids).stream().collect(Collectors.toUnmodifiableMap(Url::getId, Function.identity()));
    }

    private int enqueue(final NotificationType event, final String dedupeKey, final List<NotificationChannel> channels,
            final Map<Long, ChannelContent> contentByOwnerId) {
        try {
            return notificationDeliveryService.enqueue(event, dedupeKey, channels, contentByOwnerId);
        } catch (final RuntimeException exception) {
            log.warn("Click summaries couldn't be queued event={} exception={}", event, exception.getClass().getName());
            return 0;
        }
    }

    private TransactionTemplate readOnly() {
        final var template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    private static SummaryLink summaryLink(final Url url, final LinkDayCounts count) {
        return new SummaryLink(url.getShortUrl(), DomainNames.hostOf(url.getLongUrl()).orElse(null), count.clicks(), count.uniqueVisitors(),
                count.botClicks());
    }

}
