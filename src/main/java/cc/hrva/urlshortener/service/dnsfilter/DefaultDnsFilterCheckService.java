package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DefaultDnsFilterCheckService implements DnsFilterCheckService {

    private static final String PERFORMED_BY = "system";
    private static final String VIA = "dns-filter-check";
    private static final String FILTER_SEPARATOR = ", ";
    private static final String REVIEW_REASON_PREFIX = "blocked by DNS filter ";
    private static final Duration RECHECK_WINDOW = Duration.ofMinutes(15);
    private static final List<Duration> RECHECK_AGES = List.of(Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24));

    private final UrlRepository urlRepository;
    private final DnsFilterService dnsFilterService;
    private final SettingsService settingsService;
    private final LinkCheckService linkCheckService;
    private final ModerationService moderationService;
    private final ReviewQueueService reviewQueueService;
    private final Clock clock;

    @Override
    public DnsFilterCheckResult check(final long urlId) {
        final var url = urlRepository.findById(urlId).filter(Url::isActive);
        if (url.isEmpty() || !settingsService.isOn(SettingKey.DNS_FILTER_CHECK) || linkCheckService.isAllowListed(url.get().getLongUrl())) {
            log.info("Link is not checked against the DNS filters urlId={}", urlId);
            return DnsFilterCheckResult.SKIPPED;
        }

        final var destination = DomainNames.hostOf(url.get().getLongUrl()).filter(host -> !DomainNames.isIpLiteral(host));
        if (destination.isEmpty()) {
            return DnsFilterCheckResult.SKIPPED;
        }

        final var threshold = settingsService.number(SettingKey.DNS_FILTERS_TO_BLOCK);
        final var reasons = new ArrayList<String>();
        var unanswered = false;
        for (final var host : hostsOf(url.get(), destination.get())) {
            final var report = dnsFilterService.lookup(host);
            if (!report.exists()) {
                continue;
            }
            final var blocking = report.blocking();
            if (blocking.size() >= threshold) {
                block(url.get(), host, blocking);
                return DnsFilterCheckResult.BLOCKED;
            }
            blocking.forEach(provider -> reasons.add(reasonOf(host, destination.get(), provider)));
            unanswered |= report.anyUnanswered();
        }
        if (!reasons.isEmpty()) {
            reviewQueueService.flag(url.get(), reasons);
            return DnsFilterCheckResult.FLAGGED;
        }
        return unanswered ? DnsFilterCheckResult.RETRY : DnsFilterCheckResult.CLEAN;
    }

    @Async(AsyncExecutorConfiguration.DNS_FILTER_EXECUTOR)
    @Override
    public void checkLater(final long urlId) {
        try {
            check(urlId);
        } catch (final RuntimeException exception) {
            log.warn("Could not check a link against the DNS filters urlId={}", urlId, exception);
        }
    }

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Override
    public void recheckFresh() {
        final var now = LocalDateTime.now(clock);
        final var ids = new LinkedHashSet<Long>();
        RECHECK_AGES.forEach(age -> ids.addAll(urlRepository.findActiveIdsCreatedBetween(now.minus(age).minus(RECHECK_WINDOW), now.minus(age))));
        final var results = checkAll(ids);
        log.info("DNS filter recheck of new links finished links={} results={}", ids.size(), results);
    }

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Override
    public void sweep() {
        final var ids = urlRepository.findActiveIds();
        final var results = checkAll(ids);
        log.info("DNS filter sweep finished links={} results={}", ids.size(), results);
    }

    private Map<DnsFilterCheckResult, Integer> checkAll(final Collection<Long> ids) {
        final var results = new EnumMap<DnsFilterCheckResult, Integer>(DnsFilterCheckResult.class);
        Arrays.stream(DnsFilterCheckResult.values()).forEach(result -> results.put(result, 0));
        var failed = 0;
        for (final var id : ids) {
            try {
                results.merge(check(id), 1, Integer::sum);
            } catch (final RuntimeException exception) {
                failed++;
                log.warn("Could not check a link against the DNS filters urlId={}", id, exception);
            }
        }
        if (failed > 0) {
            log.warn("Links that could not be checked against the DNS filters failed={}", failed);
        }
        return results;
    }

    private List<String> hostsOf(final Url url, final String destination) {
        return Stream.concat(Stream.of(destination), Stream.ofNullable(url.getFinalUrl())
                        .filter(finalUrl -> !linkCheckService.isAllowListed(finalUrl))
                        .map(DomainNames::hostOf)
                        .flatMap(Optional::stream)
                        .filter(host -> !DomainNames.isIpLiteral(host)))
                .distinct()
                .toList();
    }

    private static String reasonOf(final String host, final String destination, final DnsFilterProvider provider) {
        final var reason = REVIEW_REASON_PREFIX + provider.label();
        return host.equals(destination) ? reason : SuspiciousUrlService.redirectReason(host, reason);
    }

    private void block(final Url url, final String host, final List<DnsFilterProvider> blocking) {
        final var detail = blocking.stream().map(DnsFilterProvider::label).collect(Collectors.joining(FILTER_SEPARATOR));
        log.warn("Link blocked by the DNS filters urlId={} host={} detail={}", url.getId(), host, detail);
        Stream.concat(Stream.of(url.getId()), urlRepository.findActiveIdsPointingAt(host).stream())
                .distinct()
                .forEach(id -> moderationService.blockLink(id, BlockReason.DNS_FILTERS, detail, PERFORMED_BY, VIA));
    }

}
