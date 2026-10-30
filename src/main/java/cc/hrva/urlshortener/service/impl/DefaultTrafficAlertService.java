package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository.BusyLink;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.TrafficAlertService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.AnonymousLinkBusy;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.AnonymousTrafficShare;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Duration;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultTrafficAlertService implements TrafficAlertService {

    private static final String LINK_KEY_PREFIX = "traffic:link:";
    private static final String SHARE_KEY_PREFIX = "traffic:share:";
    private static final String ALERTED = "1";
    private static final Duration ALERTED_TIME_TO_LIVE = Duration.ofHours(48);
    private static final int MAX_BUSY_LINKS = 100;

    private final AnonymousTrafficRepository anonymousTrafficRepository;
    private final AnalyticsDays analyticsDays;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final SettingsService settingsService;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void check() {
        if (!settingsService.isOn(SettingKey.TRAFFIC_ALERTS)) {
            return;
        }
        final var day = analyticsDays.today();
        try {
            alertBusyLinks(day);
            alertTrafficShare(day);
        } catch (final RuntimeException exception) {
            log.warn("Traffic check skipped day={} exception={}", day, exception.getClass().getName());
        }
    }

    private void alertBusyLinks(final LocalDate day) {
        final var linkClicks = settingsService.number(SettingKey.TRAFFIC_LINK_CLICKS);
        for (final var link : anonymousTrafficRepository.busyAnonymousLinks(day, linkClicks, MAX_BUSY_LINKS)) {
            if (firstToday(LINK_KEY_PREFIX + day + ":" + link.urlId())) {
                eventPublisher.publishEvent(new AnonymousLinkBusy(alerted(link), link.clicks(), topCountry(link.urlId(), day)));
                log.info("Busy link without an account urlId={} clicks={}", link.urlId(), link.clicks());
            }
        }
    }

    private void alertTrafficShare(final LocalDate day) {
        final var clicks = anonymousTrafficRepository.dayClicks(day);
        if (clicks.anonymous() < settingsService.number(SettingKey.TRAFFIC_MIN_ANONYMOUS_CLICKS)
                || clicks.anonymous() * 100 <= clicks.total() * settingsService.number(SettingKey.TRAFFIC_SHARE_PERCENT)) {
            return;
        }
        if (firstToday(SHARE_KEY_PREFIX + day)) {
            eventPublisher.publishEvent(new AnonymousTrafficShare(clicks.anonymous(), clicks.total()));
            log.info("Traffic share without an account anonymous={} total={}", clicks.anonymous(), clicks.total());
        }
    }

    private String topCountry(final long urlId, final LocalDate day) {
        try {
            return anonymousTrafficRepository.topCountry(urlId, day).orElse(null);
        } catch (final RuntimeException exception) {
            log.warn("Top country not read, alerting without it urlId={} exception={}", urlId, exception.getClass().getName());
            return null;
        }
    }

    private boolean firstToday(final String key) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, ALERTED, ALERTED_TIME_TO_LIVE));
    }

    private static AlertedLink alerted(final BusyLink link) {
        return new AlertedLink(link.urlId(), link.shortUrl(), DomainNames.hostOf(link.longUrl()).orElse(null), null, null);
    }

}
