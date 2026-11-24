package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OwnHostsCheck {

    private static final String ALERTED_KEY_PREFIX = "own-host-blocked:";
    private static final Duration ALERT_REPEATS_AFTER = Duration.ofDays(7);
    private static final String SEPARATOR = ",";

    private final DnsFilterService dnsFilterService;
    private final CustomDomainRepository customDomainRepository;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;

    public void run() {
        final var hosts = new LinkedHashSet<String>();
        Stream.of(appProperties.getServerUrl(), appProperties.getFrontendUrl())
                .map(DomainNames::hostOf)
                .flatMap(Optional::stream)
                .forEach(hosts::add);
        customDomainRepository.findByStatusIn(Set.of(CustomDomainStatus.ACTIVE)).stream()
                .map(CustomDomain::getHostname)
                .forEach(hosts::add);
        hosts.forEach(this::check);
        log.info("DNS filter check of our own hosts finished hosts={}", hosts.size());
    }

    private void check(final String host) {
        try {
            final var report = dnsFilterService.lookup(host);
            if (!report.exists()) {
                return;
            }
            if (!report.blocking().isEmpty()) {
                log.warn("DNS filters block our own host host={} filters={}", host, labelsOf(report.blocking()));
            }
            alertedFiltersOf(host).ifPresentOrElse(alerted -> alertWhenNewlyBlocked(host, alerted, report), () -> alertWithoutHistory(host, report));
        } catch (final RuntimeException exception) {
            log.warn("Could not check our own host against the DNS filters host={}", host, exception);
        }
    }

    private void alertWhenNewlyBlocked(final String host, final Set<DnsFilterProvider> alerted, final DnsFilterReport report) {
        final var answered = report.answers().stream()
                .filter(answer -> answer.status() != DnsFilterStatus.NO_ANSWER)
                .map(DnsFilterAnswer::provider)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DnsFilterProvider.class)));
        final var blocking = EnumSet.noneOf(DnsFilterProvider.class);
        alerted.stream().filter(provider -> !answered.contains(provider)).forEach(blocking::add);
        blocking.addAll(report.blocking());
        if (blocking.equals(alerted)) {
            return;
        }
        remember(host, blocking);
        if (!alerted.containsAll(blocking)) {
            eventPublisher.publishEvent(new AdminAlertEvent.OwnHostBlocked(host, labelsOf(blocking)));
        }
    }

    private void alertWithoutHistory(final String host, final DnsFilterReport report) {
        if (!report.blocking().isEmpty()) {
            eventPublisher.publishEvent(new AdminAlertEvent.OwnHostBlocked(host, labelsOf(EnumSet.copyOf(report.blocking()))));
        }
    }

    private Optional<Set<DnsFilterProvider>> alertedFiltersOf(final String host) {
        try {
            final var alerted = Optional.ofNullable(redisTemplate.opsForValue().get(ALERTED_KEY_PREFIX + host)).orElse("");
            return Optional.of(Arrays.stream(alerted.split(SEPARATOR))
                    .map(name -> EnumUtils.getEnum(DnsFilterProvider.class, name))
                    .filter(provider -> provider != null)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(DnsFilterProvider.class))));
        } catch (final RuntimeException exception) {
            log.warn("Could not read the last alert for our own host, alerting without it host={} exception={}", host, exception.getClass().getName());
            return Optional.empty();
        }
    }

    private void remember(final String host, final Set<DnsFilterProvider> blocking) {
        try {
            if (blocking.isEmpty()) {
                redisTemplate.delete(ALERTED_KEY_PREFIX + host);
            } else {
                redisTemplate.opsForValue().set(ALERTED_KEY_PREFIX + host,
                        blocking.stream().map(DnsFilterProvider::name).collect(Collectors.joining(SEPARATOR)), ALERT_REPEATS_AFTER);
            }
        } catch (final RuntimeException exception) {
            log.warn("Could not record the last alert for our own host host={} exception={}", host, exception.getClass().getName());
        }
    }

    private static List<String> labelsOf(final Collection<DnsFilterProvider> providers) {
        return providers.stream().map(DnsFilterProvider::label).toList();
    }

}
