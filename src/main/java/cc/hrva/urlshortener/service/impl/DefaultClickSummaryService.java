package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.LinkClicks;
import cc.hrva.urlshortener.dto.LinkDayCounts;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.dto.notification.DailySummary.SummaryLink;
import cc.hrva.urlshortener.dto.notification.WeeklySummary;
import cc.hrva.urlshortener.dto.notification.WeeklySummary.EndingSoon;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationClaimRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.ClickSummaryService;
import cc.hrva.urlshortener.service.NotificationDeliveryService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.digest.DigestWeek;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
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
    private static final String WEEKLY_KEY = "summary.weekly:";
    private static final String WEEKLY_EMAIL_CLAIM = "weekly-email:";
    private static final int TOP_LINKS = 5;
    private static final int ENDING_SOON_DAYS = 7;

    private final NotificationChannelRepository notificationChannelRepository;
    private final LinkStatsRepository linkStatsRepository;
    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final NotificationClaimRepository notificationClaimRepository;
    private final EmailConsentReader emailConsentReader;
    private final SendingEmailService sendingEmailService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final NotificationMessages notificationMessages;
    private final NotificationPayloads notificationPayloads;
    private final AnalyticsDays analyticsDays;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

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

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendDailyInBackground() {
        sendDaily();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendWeekly() {
        final var week = DigestWeek.lastClosedBefore(analyticsDays.today());
        final var recipients = Objects.requireNonNull(readOnly().execute(status ->
                new WeeklyRecipients(emailConsentReader.allConsenters(NotificationType.WEEKLY_SUMMARY_EMAIL), channelsWith(NotificationType.WEEKLY_SUMMARY))));
        final var channelOwners = recipients.channels().stream().map(channel -> channel.getOwner().getId()).collect(Collectors.toSet());
        final var userIds = new TreeSet<Long>(recipients.emailConsenters());
        userIds.addAll(channelOwners);
        final var now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        final var contentByOwnerId = new LinkedHashMap<Long, ChannelContent>();
        var emails = 0;
        for (final var userId : userIds) {
            try {
                final var summary = readOnly().execute(status -> weekly(userId, week, now));
                if (summary == null || !summary.hasNews()) {
                    continue;
                }
                if (channelOwners.contains(userId)) {
                    channelContent(summary).ifPresent(content -> contentByOwnerId.put(userId, content));
                }
                if (recipients.emailConsenters().contains(userId) && emailed(summary, week, now)) {
                    emails++;
                }
            } catch (final RuntimeException exception) {
                log.warn("Weekly summary couldn't be collected user id={} exception={}", userId, exception.getClass().getName());
            }
        }
        final var queued = contentByOwnerId.isEmpty() ? 0 : enqueue(NotificationType.WEEKLY_SUMMARY, WEEKLY_KEY + week.from(), recipients.channels(),
                contentByOwnerId);
        log.info("Weekly summaries week={} users={} emails={} channelUsers={} deliveries={}", week.from(), userIds.size(), emails,
                contentByOwnerId.size(), queued);
    }

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendWeeklyInBackground() {
        sendWeekly();
    }

    private List<NotificationChannel> channelsWith(final NotificationType event) {
        return notificationChannelRepository.findEnabledWithEvent(event);
    }

    private Optional<ChannelContent> channelContent(final WeeklySummary summary) {
        try {
            return Optional.of(new ChannelContent(notificationPayloads.weekly(summary), notificationMessages.weekly(summary)));
        } catch (final RuntimeException exception) {
            log.warn("Weekly summary channel content couldn't be built user id={} exception={}", summary.userId(), exception.getClass().getName());
            return Optional.empty();
        }
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

    private WeeklySummary weekly(final long userId, final DigestWeek week, final LocalDateTime now) {
        final var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        final var days = linkStatsRepository.findOwnerDaily(userId, week.from(), week.to());
        final var weekBefore = week.previous();
        final var clicksBefore = linkStatsRepository.findOwnerDaily(userId, weekBefore.from(), weekBefore.to()).stream()
                .mapToLong(AnalyticsDay::clicks)
                .sum();
        final var clicks = new WeeklyDigest.Change(days.stream().mapToLong(AnalyticsDay::clicks).sum(), clicksBefore);
        final var uniqueVisitors = days.stream().mapToLong(AnalyticsDay::uniqueVisitors).sum();
        final var top = linkStatsRepository.findOwnerTopLinks(userId, week.from(), week.to(), TOP_LINKS);
        final var topById = linksById(top.stream().map(LinkClicks::urlId).toList());
        final var topLinks = top.stream()
                .filter(link -> topById.containsKey(link.urlId()))
                .map(link -> topLink(topById.get(link.urlId()), link.clicks()))
                .toList();
        final var deadFrom = startOf(week.from());
        final var deadTo = startOf(week.to().plusDays(1));
        final var dead = urlRepository.findOwnerLinksDeadBetween(userId, deadFrom, deadTo, Limit.of(NotificationMessages.MOST_LINKS + 1));
        final var moreDead = dead.size() > NotificationMessages.MOST_LINKS
                ? urlRepository.countOwnerLinksDeadBetween(userId, deadFrom, deadTo) - NotificationMessages.MOST_LINKS
                : 0;
        final var deadLinks = dead.stream()
                .limit(NotificationMessages.MOST_LINKS)
                .map(url -> new DeadLink(url.getId(), userId, url.getShortUrl(), hostOf(url), url.getDestinationDeadSince(),
                        url.getDestinationLastStatus(), ShortLinks.hostnameOf(url)))
                .toList();
        final var until = now.plusDays(ENDING_SOON_DAYS);
        final var ending = urlRepository.findOwnerLinksEndingSoon(userId, now, until, Limit.of(NotificationMessages.MOST_LINKS + 1));
        final var moreEnding = ending.size() > NotificationMessages.MOST_LINKS
                ? urlRepository.countOwnerLinksEndingSoon(userId, now, until) - NotificationMessages.MOST_LINKS
                : 0;
        final var endingSoon = ending.stream()
                .limit(NotificationMessages.MOST_LINKS)
                .map(url -> endingSoon(url, now))
                .toList();
        return new WeeklySummary(userId, user.getEmail(), week.from(), week.to(), clicks, uniqueVisitors, topLinks, deadLinks, endingSoon,
                Math.max(0, moreDead), Math.max(0, moreEnding));
    }

    private EndingSoon endingSoon(final Url url, final LocalDateTime now) {
        final var expiration = url.getExpirationDate();
        if (expiration != null && expiration.isAfter(now) && !expiration.isAfter(now.plusDays(ENDING_SOON_DAYS))) {
            return new EndingSoon(url.getShortUrl(), hostOf(url), WeeklySummary.EXPIRES, notificationMessages.day(expiration), null,
                    ShortLinks.hostnameOf(url));
        }
        final var visitsLeft = url.getVisitLimit() - Objects.requireNonNullElse(url.getVisits(), 0L);
        return new EndingSoon(url.getShortUrl(), hostOf(url), WeeklySummary.VISIT_LIMIT, null, visitsLeft, ShortLinks.hostnameOf(url));
    }

    private boolean emailed(final WeeklySummary summary, final DigestWeek week, final LocalDateTime now) {
        try {
            if (!notificationClaimRepository.claim(summary.userId(), WEEKLY_EMAIL_CLAIM + week.from(), now)) {
                log.info("Weekly summary email already claimed user id={} week={}", summary.userId(), week.from());
                return false;
            }
            sendingEmailService.sendWeeklySummaryEmail(summary);
            return true;
        } catch (final RuntimeException exception) {
            log.warn("Weekly summary email couldn't be queued user id={} exception={}", summary.userId(), exception.getClass().getName());
            return false;
        }
    }

    private LocalDateTime startOf(final LocalDate day) {
        return LocalDateTime.ofInstant(day.atStartOfDay(analyticsDays.zone()).toInstant(), clock.getZone());
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
        return new SummaryLink(url.getShortUrl(), hostOf(url), count.clicks(), count.uniqueVisitors(), count.botClicks(), ShortLinks.hostnameOf(url));
    }

    private static WeeklyDigest.TopLink topLink(final Url url, final long clicks) {
        return new WeeklyDigest.TopLink(url.getShortUrl(), hostOf(url), clicks, ShortLinks.hostnameOf(url));
    }

    private static String hostOf(final Url url) {
        return DomainNames.hostOf(url.getLongUrl()).orElse(null);
    }

    private record WeeklyRecipients(Set<Long> emailConsenters, List<NotificationChannel> channels) {
    }

}
