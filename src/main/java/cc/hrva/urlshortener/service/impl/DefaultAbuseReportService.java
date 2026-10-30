package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.ReviewBulkResolveDto;
import cc.hrva.urlshortener.dto.abuse.AbuseReportLink;
import cc.hrva.urlshortener.dto.abuse.AbuseReportRequest;
import cc.hrva.urlshortener.dto.abuse.AbuseReportResponse;
import cc.hrva.urlshortener.dto.abuse.ReportedDomainsDto;
import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.model.ReportedDomain;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.ReportedDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.AbuseReportService;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.abuse.AbuseReportComposer;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainsSuspended;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.rdap.DomainRecord;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.EmailAddresses;
import com.google.common.collect.Lists;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAbuseReportService implements AbuseReportService {

    private static final String URL_IDS = "urlIds";
    private static final String TARGET_REGISTRAR = "Registrar";
    private static final String UNKNOWN_REGISTRAR = "unknown";
    private static final int IDS_PER_QUERY = 1_000;
    private static final int RECHECK_BATCH = 20;
    private static final int TOP_COUNTRIES = 3;
    private static final String PENDING_PREFIX = "abuse:pending-suspension:";
    private static final String PENDING = "1";
    private static final Duration PENDING_TIME_TO_LIVE = Duration.ofHours(36);

    private final CampaignService campaignService;
    private final UrlRepository urlRepository;
    private final LinkStatsRepository linkStatsRepository;
    private final ReportedDomainRepository reportedDomainRepository;
    private final DomainAgeService domainAgeService;
    private final AbuseReportComposer abuseReportComposer;
    private final SettingsService settingsService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final AnalyticsDays analyticsDays;
    private final Clock clock;

    @Override
    public AbuseReportResponse prepare(final AbuseReportRequest request) {
        final var linksByDomain = new TreeMap<String, List<AbuseReportLink>>();
        for (final var chunk : Lists.partition(linkIdsOf(request), IDS_PER_QUERY)) {
            for (final var link : urlRepository.findAbuseReportLinksByIdIn(chunk)) {
                domainOf(link.longUrl()).ifPresent(domain -> linksByDomain.computeIfAbsent(domain, key -> new ArrayList<>()).add(link));
            }
        }
        if (linksByDomain.isEmpty()) {
            return new AbuseReportResponse(List.of(), List.of());
        }

        final var domains = List.copyOf(linksByDomain.keySet());
        final var records = domainAgeService.records(domains.stream().filter(DefaultAbuseReportService::isRegistryDomain).toList(), false);
        final var reports = reportsOf(domains);
        final var allLinks = linksByDomain.values().stream().flatMap(List::stream).toList();
        final var clicks = clicksOf(allLinks);
        final var groups = new LinkedHashMap<String, List<DomainRecord>>();
        final var noContact = new ArrayList<AbuseReportResponse.NoContact>();
        for (final var domain : domains) {
            if (!isRegistryDomain(domain)) {
                noContact.add(new AbuseReportResponse.NoContact(domain, null, false, statusOf(reports, domain)));
                continue;
            }
            final var record = Optional.ofNullable(records.get(domain)).orElseGet(() -> DomainRecord.failed(domain));
            if (record.status() == DomainRecord.Status.FOUND && EmailAddresses.isValid(record.abuseEmail())) {
                groups.computeIfAbsent(record.abuseEmail().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(withDomain(record, domain));
            } else {
                noContact.add(new AbuseReportResponse.NoContact(domain, record.registrar(), record.status() == DomainRecord.Status.FAILED,
                        statusOf(reports, domain)));
            }
        }

        final var cc = settingsService.addresses(SettingKey.ABUSE_CC);
        final var cards = groups.values().stream()
                .map(group -> report(group, linksByDomain, clicks, reports, cc))
                .sorted(Comparator.comparingInt((AbuseReportResponse.Report report) -> report.domains().size()).reversed()
                        .thenComparing(AbuseReportResponse.Report::registrar)
                        .thenComparing(AbuseReportResponse.Report::to))
                .toList();
        return new AbuseReportResponse(cards, noContact);
    }

    @Override
    public void markReported(final ReportedDomainsDto request, final String performedBy) {
        final var now = LocalDateTime.now(clock);
        final var registrar = StringUtils.trimToNull(request.registrar());
        final var abuseEmail = StringUtils.trimToNull(request.abuseEmail());
        final var domains = request.domains().stream().map(domain -> domain.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        for (final var domain : domains) {
            try {
                reportedDomainRepository.upsert(domain, registrar, abuseEmail, performedBy, now);
            } catch (final DataIntegrityViolationException exception) {
                reportedDomainRepository.upsert(domain, registrar, abuseEmail, performedBy, now);
            }
        }
        auditLogService.log(performedBy, AuditAction.REPORT_DOMAINS, TARGET_REGISTRAR, abuseEmail,
                "domains: %d, registrar: %s".formatted(domains.size(), Objects.requireNonNullElse(registrar, UNKNOWN_REGISTRAR)));
    }

    @Override
    public void recheck() {
        final var now = LocalDateTime.now(clock);
        final var domains = reportedDomainRepository.findToRecheck(now.minusDays(settingsService.number(SettingKey.ABUSE_RECHECK_DAYS))).stream()
                .filter(DefaultAbuseReportService::isRegistryDomain)
                .toList();
        final var suspended = new ArrayList<String>();
        var failed = 0;
        var pending = 0;
        for (final var batch : Lists.partition(domains, RECHECK_BATCH)) {
            final var records = domainAgeService.records(batch, true);
            final var checked = new ArrayList<String>();
            for (final var domain : batch) {
                final var record = records.get(domain);
                if (record == null || record.status() == DomainRecord.Status.FAILED) {
                    failed++;
                } else if (!record.suspended()) {
                    forgetPending(domain);
                    checked.add(domain);
                } else if (!wasPending(domain)) {
                    markPending(domain);
                    checked.add(domain);
                    pending++;
                } else {
                    if (reportedDomainRepository.markSuspended(domain, now) == 1) {
                        suspended.add(domain);
                    }
                    forgetPending(domain);
                }
            }
            if (!checked.isEmpty()) {
                reportedDomainRepository.markChecked(checked, now);
            }
        }
        log.info("Reported domains rechecked domains={} suspended={} pending={} failed={}", domains.size(), suspended.size(), pending, failed);
        if (!suspended.isEmpty()) {
            eventPublisher.publishEvent(new DomainsSuspended(suspended));
        }
    }

    private boolean wasPending(final String domain) {
        try {
            return redisTemplate.opsForValue().get(PENDING_PREFIX + domain) != null;
        } catch (final RuntimeException exception) {
            log.warn("Pending suspension read failed, not confirming domain={} exception={}", domain, exception.getClass().getName());
            return false;
        }
    }

    private void markPending(final String domain) {
        try {
            redisTemplate.opsForValue().set(PENDING_PREFIX + domain, PENDING, PENDING_TIME_TO_LIVE);
        } catch (final RuntimeException exception) {
            log.warn("Pending suspension write failed domain={} exception={}", domain, exception.getClass().getName());
        }
    }

    private void forgetPending(final String domain) {
        try {
            redisTemplate.delete(PENDING_PREFIX + domain);
        } catch (final RuntimeException exception) {
            log.warn("Pending suspension delete failed domain={} exception={}", domain, exception.getClass().getName());
        }
    }

    private static boolean isRegistryDomain(final String domain) {
        return DomainNames.registryDomain(domain).equals(domain);
    }

    private List<Long> linkIdsOf(final AbuseReportRequest request) {
        if (request.urlIds() == null == (request.campaignId() == null)) {
            throw new FieldValidationException(URL_IDS, ReviewBulkResolveDto.LINKS_REQUIRED);
        }
        return request.urlIds() != null ? request.urlIds() : campaignService.linkIds(request.campaignId());
    }

    private static Optional<String> domainOf(final String longUrl) {
        return DomainNames.hostOf(longUrl)
                .filter(host -> !DomainNames.isIpLiteral(host))
                .map(DomainNames::registrableDomain);
    }

    private static DomainRecord withDomain(final DomainRecord record, final String domain) {
        return new DomainRecord(domain, record.status(), record.registeredOn(), record.registrar(), record.abuseEmail(), record.statuses());
    }

    private AbuseReportResponse.Report report(final List<DomainRecord> group, final Map<String, List<AbuseReportLink>> linksByDomain,
                                              final Map<Long, Long> clicks, final Map<String, ReportedDomain> reports, final List<String> cc) {
        final var to = group.getFirst().abuseEmail();
        final var registrar = registrarOf(group).orElseGet(() -> StringUtils.substringAfterLast(to, "@"));
        final var links = group.stream().flatMap(record -> linksByDomain.get(record.domain()).stream())
                .sorted(Comparator.comparing(AbuseReportLink::id))
                .toList();
        final var email = abuseReportComposer.compose(registrar,
                group.stream().map(record -> new AbuseReportComposer.Domain(record.domain(), record.registeredOn())).toList(),
                links.stream().map(link -> new AbuseReportComposer.Link(link.id(), link.longUrl(), link.finalUrl(), analyticsDate(link.createDate()),
                        link.active(), clicks.getOrDefault(link.id(), 0L))).toList(),
                linkStatsRepository.topCountries(links.stream().map(AbuseReportLink::id).toList(), TOP_COUNTRIES));
        return new AbuseReportResponse.Report(registrar, to, cc, email.subject(), email.body(), group.stream()
                .map(record -> new AbuseReportResponse.Domain(record.domain(), record.registeredOn(), statusOf(reports, record.domain())))
                .toList());
    }

    private static Optional<String> registrarOf(final List<DomainRecord> group) {
        return group.stream()
                .map(DomainRecord::registrar)
                .filter(StringUtils::isNotBlank)
                .map(String::strip)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .min(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
    }

    private Map<Long, Long> clicksOf(final List<AbuseReportLink> links) {
        final var from = links.stream().map(AbuseReportLink::createDate).filter(Objects::nonNull).map(this::analyticsDate)
                .min(Comparator.naturalOrder());
        if (from.isEmpty()) {
            return Map.of();
        }
        final var clicks = new HashMap<Long, Long>();
        for (final var chunk : Lists.partition(links.stream().map(AbuseReportLink::id).toList(), IDS_PER_QUERY)) {
            clicks.putAll(linkStatsRepository.sumClicksByUrl(chunk, from.get(), analyticsDays.today()));
        }
        return clicks;
    }

    private Map<String, ReportedDomain> reportsOf(final Collection<String> domains) {
        final var reports = new HashMap<String, ReportedDomain>();
        for (final var chunk : Lists.partition(List.copyOf(domains), IDS_PER_QUERY)) {
            reportedDomainRepository.findAllById(chunk).forEach(report -> reports.put(report.getDomain(), report));
        }
        return reports;
    }

    private static ReportStatus statusOf(final Map<String, ReportedDomain> reports, final String domain) {
        return Optional.ofNullable(reports.get(domain)).map(ReportStatus::from).orElse(null);
    }

    private LocalDate analyticsDate(final LocalDateTime stored) {
        return stored == null ? null : stored.atZone(clock.getZone()).withZoneSameInstant(analyticsDays.zone()).toLocalDate();
    }

}
