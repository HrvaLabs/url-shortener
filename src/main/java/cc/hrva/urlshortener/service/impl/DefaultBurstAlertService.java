package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BurstDetected;
import cc.hrva.urlshortener.service.burst.CreatedLink;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultBurstAlertService implements BurstAlertService {

    private static final String ALERTED_KEY_PREFIX = "burst:alerted:";
    private static final String MEMBER_SEPARATOR = "|";
    private static final String ALERTED = "1";
    private static final Duration ALERT_SILENCE = Duration.ofHours(24);
    private static final int MIN_CREATORS = 2;
    private static final int NEW_DOMAIN_DAYS = 30;

    private final RedisBurstCounter redisBurstCounter;
    private final DomainAgeService domainAgeService;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final Clock clock;

    @Async
    @Override
    public void linkCreated(final CreatedLink link) {
        if (link.outcome() == LinkVerdict.Outcome.ALLOWED) {
            return;
        }
        final var host = DomainNames.hostOf(link.longUrl()).filter(candidate -> !DomainNames.isIpLiteral(candidate));
        if (host.isEmpty()) {
            return;
        }
        final var domain = DomainNames.registrableDomain(host.get());
        try {
            alertIfBurst(link, host.get(), domain);
        } catch (final RuntimeException exception) {
            log.warn("Burst check skipped domain={} exception={}", domain, exception.getClass().getName());
        }
    }

    private void alertIfBurst(final CreatedLink link, final String host, final String domain) {
        final var burst = appProperties.getBurst();
        final var members = redisBurstCounter.record(domain, link.urlId() + MEMBER_SEPARATOR + link.creator(), clock.instant(), burst.getWindow());
        final var creators = creatorsOf(members);
        if (members.size() < burst.getLinks() || creators < MIN_CREATORS) {
            return;
        }
        final var alertedKey = ALERTED_KEY_PREFIX + domain;
        if (redisTemplate.opsForValue().get(alertedKey) != null) {
            return;
        }
        final var today = LocalDate.now(clock);
        final var registeredOn = domainAgeService.registeredOn(host);
        if (registeredOn.filter(date -> !date.isAfter(today.minusDays(NEW_DOMAIN_DAYS))).isPresent()) {
            return;
        }
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(alertedKey, ALERTED, ALERT_SILENCE))) {
            return;
        }
        final var ageDays = ageInDays(registeredOn, today);
        eventPublisher.publishEvent(new BurstDetected(domain, members.size(), creators, ageDays, burst.getWindow()));
        log.info("Burst of new links domain={} links={} creators={} ageDays={}", domain, members.size(), creators, ageDays);
    }

    private static int creatorsOf(final List<String> members) {
        return (int) members.stream().map(member -> StringUtils.substringAfter(member, MEMBER_SEPARATOR)).distinct().count();
    }

    private static Integer ageInDays(final Optional<LocalDate> registeredOn, final LocalDate today) {
        return registeredOn.map(date -> (int) Math.max(0, ChronoUnit.DAYS.between(date, today))).orElse(null);
    }

}
