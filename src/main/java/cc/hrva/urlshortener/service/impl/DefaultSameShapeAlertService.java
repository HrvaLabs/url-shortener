package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SameShapeAlertService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.SameShapeDetected;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import cc.hrva.urlshortener.service.campaign.UrlShapes;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
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
public class DefaultSameShapeAlertService implements SameShapeAlertService {

    private static final String SHAPE_KEY_PREFIX = "campaign:shape:";
    private static final String ALERTED_KEY_PREFIX = "campaign:alerted:";
    private static final String MEMBER_SEPARATOR = "|";
    private static final String ALERTED = "1";
    private static final Duration ALERT_SILENCE = Duration.ofHours(24);
    private static final int MAX_EXAMPLES = 3;

    private final RedisBurstCounter redisBurstCounter;
    private final UrlRepository urlRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final Clock clock;

    @Async
    @Override
    public void anonymousLinkCreated(final long urlId, final String longUrl, final LinkVerdict.Outcome outcome) {
        if (!appProperties.getCampaign().isEnabled() || outcome == LinkVerdict.Outcome.ALLOWED) {
            return;
        }
        final var host = DomainNames.hostOf(longUrl).filter(candidate -> !DomainNames.isIpLiteral(candidate));
        final var shape = UrlShapes.of(longUrl);
        if (host.isEmpty() || shape.isEmpty()) {
            return;
        }
        try {
            alertIfSameShape(urlId, shape.get(), DomainNames.registrableDomain(host.get()));
        } catch (final RuntimeException exception) {
            log.warn("Same-shape check skipped shape={} exception={}", shape.get().key(), exception.getClass().getName());
        }
    }

    private void alertIfSameShape(final long urlId, final UrlShape shape, final String domain) {
        final var campaign = appProperties.getCampaign();
        final var members = redisBurstCounter.recordUnderKey(SHAPE_KEY_PREFIX + shape.key(), urlId + MEMBER_SEPARATOR + domain, clock.instant(),
                campaign.getWindow());
        final var urlIds = urlIdsOf(members);
        final var domains = domainsOf(members);
        if (urlIds.size() < campaign.getLinks() || domains < campaign.getDomains()) {
            return;
        }
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(ALERTED_KEY_PREFIX + shape.key(), ALERTED, ALERT_SILENCE))) {
            return;
        }
        eventPublisher.publishEvent(new SameShapeDetected(shape.display(), shape.path(), urlIds.size(), domains, campaign.getWindow(),
                examplesOf(shape, urlIds)));
        log.info("Same-shape links shape={} display={} links={} domains={}", shape.key(), shape.display(), urlIds.size(), domains);
    }

    private List<String> examplesOf(final UrlShape shape, final List<Long> urlIds) {
        try {
            return newestAliases(urlIds);
        } catch (final RuntimeException exception) {
            log.warn("Same-shape examples skipped shape={} exception={}", shape.key(), exception.getClass().getName());
            return List.of();
        }
    }

    private List<String> newestAliases(final List<Long> urlIds) {
        final var newest = urlIds.subList(Math.max(0, urlIds.size() - MAX_EXAMPLES), urlIds.size()).reversed();
        final var aliases = urlRepository.findAllById(newest).stream().collect(Collectors.toMap(Url::getId, Url::getShortUrl, (first, second) -> first));
        return newest.stream().filter(aliases::containsKey).map(aliases::get).toList();
    }

    private static List<Long> urlIdsOf(final List<String> members) {
        return members.stream().map(member -> Long.parseLong(StringUtils.substringBefore(member, MEMBER_SEPARATOR))).distinct().toList();
    }

    private static int domainsOf(final List<String> members) {
        return (int) members.stream().map(member -> StringUtils.substringAfter(member, MEMBER_SEPARATOR)).distinct().count();
    }

}
