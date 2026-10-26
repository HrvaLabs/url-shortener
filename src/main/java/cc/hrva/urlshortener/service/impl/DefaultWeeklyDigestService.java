package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.DiscordWebhookClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.AdminStatsService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.WeeklyDigestService;
import cc.hrva.urlshortener.service.alert.DiscordAlertFormatter;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.digest.DigestWeek;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultWeeklyDigestService implements WeeklyDigestService {

    private static final int TOP_LINKS = 5;
    private static final String WEEK_KEY_PREFIX = "digest:weekly:";
    private static final Duration WEEK_KEY_TIME_TO_LIVE = Duration.ofDays(8);
    private static final Duration DISCORD_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> MANUAL_BLOCKS = BlockReason.MANUAL.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final AdminStatsService adminStatsService;
    private final AnalyticsDays analyticsDays;
    private final AppProperties appProperties;
    private final DiscordAlertFormatter discordAlertFormatter;
    private final DiscordWebhookClient discordWebhookClient;
    private final SendingEmailService sendingEmailService;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendWeeklyDigest() {
        final var webhookUrl = StringUtils.trimToEmpty(appProperties.getDiscordWebhookUrl());
        final var toDiscord = appProperties.getDigest().getWeekly().isEnabled() && !webhookUrl.isEmpty();
        final var byEmail = appProperties.getDigest().getWeekly().isEmail();
        if (!toDiscord && !byEmail) {
            log.info("Weekly digest is off");
            return;
        }

        final var digest = collect(analyticsDays.today());
        if (!claimed(digest.from())) {
            return;
        }

        final var posted = toDiscord && postedToDiscord(webhookUrl, digest);
        final var emailed = byEmail && emailQueued(digest);
        log.info("Weekly digest sent week={} discord={} email={}", digest.from(), posted, emailed);
    }

    @Override
    public WeeklyDigest collect(final LocalDate today) {
        final var week = DigestWeek.lastClosedBefore(today);
        final var weekBefore = week.previous();
        final var traffic = adminStatsService.traffic(week.from(), week.to());
        final var links = adminStatsService.links(week.from(), week.to());
        final var users = adminStatsService.users(week.from(), week.to());
        final var safety = adminStatsService.safety(week.from(), week.to());
        final var trafficBefore = adminStatsService.traffic(weekBefore.from(), weekBefore.to());
        final var linksBefore = adminStatsService.links(weekBefore.from(), weekBefore.to());
        final var usersBefore = adminStatsService.users(weekBefore.from(), weekBefore.to());

        return new WeeklyDigest(
                week.from(),
                week.to(),
                new Change(traffic.totals().clicks(), trafficBefore.totals().clicks()),
                traffic.totals().uniqueVisitors(),
                traffic.totals().botClicks(),
                new Change(links.createdInRange().total(), linksBefore.createdInRange().total()),
                totalsPerKey(links.created(), key -> true),
                new Change(total(users.signups()), total(usersBefore.signups())),
                users.activeLast7Days(),
                total(safety.reviewsOpened()),
                safety.resolved().stream().mapToLong(CountEntry::count).sum(),
                safety.openReviews(),
                totalsPerKey(safety.blocked(), key -> !MANUAL_BLOCKS.contains(key)),
                traffic.topLinks().stream()
                        .limit(TOP_LINKS)
                        .map(DefaultWeeklyDigestService::topLinkOf)
                        .toList());
    }

    private boolean claimed(final LocalDate monday) {
        try {
            final var claimed = redisTemplate.opsForValue().setIfAbsent(WEEK_KEY_PREFIX + monday, Instant.now(clock).toString(), WEEK_KEY_TIME_TO_LIVE);
            if (Boolean.TRUE.equals(claimed)) {
                return true;
            }
            log.info("Weekly digest already sent week={}", monday);
            return false;
        } catch (final RuntimeException exception) {
            log.warn("Weekly digest skipped: Redis is unavailable, so it can't make sure it is sent once exception={}", exception.getClass().getName());
            return false;
        }
    }

    private boolean postedToDiscord(final String webhookUrl, final WeeklyDigest digest) {
        try {
            return discordWebhookClient.send(webhookUrl, discordAlertFormatter.weeklyDigest(digest), DISCORD_TIMEOUT);
        } catch (final RuntimeException exception) {
            log.warn("Weekly digest couldn't be posted to Discord exception={}", exception.getClass().getName());
            return false;
        }
    }

    private boolean emailQueued(final WeeklyDigest digest) {
        try {
            sendingEmailService.sendWeeklyDigestToAdmin(digest);
            return true;
        } catch (final RuntimeException exception) {
            log.warn("Weekly digest email couldn't be queued exception={}", exception.getClass().getName());
            return false;
        }
    }

    private static List<CountEntry> totalsPerKey(final StackedSeries series, final Predicate<String> counted) {
        return series.keys().stream()
                .filter(counted)
                .map(key -> new CountEntry(key, totalOf(series, key)))
                .filter(entry -> entry.count() > 0)
                .sorted(Comparator.comparingLong(CountEntry::count).reversed())
                .toList();
    }

    private static long total(final StackedSeries series) {
        return series.daily().stream()
                .flatMap(day -> day.counts().values().stream())
                .mapToLong(Long::longValue)
                .sum();
    }

    private static long totalOf(final StackedSeries series, final String key) {
        return series.daily().stream().mapToLong(day -> day.counts().getOrDefault(key, 0L)).sum();
    }

    private static WeeklyDigest.TopLink topLinkOf(final TrafficStatsResponse.TopLink link) {
        return new WeeklyDigest.TopLink(link.shortUrl(), link.destinationHost(), link.clicks());
    }

}
