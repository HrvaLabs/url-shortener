package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OwnHostsCheck {

    private final DnsFilterService dnsFilterService;
    private final CustomDomainRepository customDomainRepository;
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
            if (!report.exists() || report.blocking().isEmpty()) {
                return;
            }
            final var filters = report.blocking().stream().map(DnsFilterProvider::label).toList();
            log.warn("DNS filters block our own host host={} filters={}", host, filters);
            eventPublisher.publishEvent(new AdminAlertEvent.OwnHostBlocked(host, filters));
        } catch (final RuntimeException exception) {
            log.warn("Could not check our own host against the DNS filters host={}", host, exception);
        }
    }

}
