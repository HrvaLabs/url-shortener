package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.campaign.CampaignDetailResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignDomainCount;
import cc.hrva.urlshortener.dto.campaign.CampaignDomainResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignLinkCounts;
import cc.hrva.urlshortener.dto.campaign.CampaignLinkResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignReportCounts;
import cc.hrva.urlshortener.dto.campaign.CampaignResponse;
import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import cc.hrva.urlshortener.exception.CampaignNotFoundException;
import cc.hrva.urlshortener.model.Campaign;
import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.CampaignLinkId;
import cc.hrva.urlshortener.model.ReportedDomain;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import cc.hrva.urlshortener.repository.CampaignLinkRepository;
import cc.hrva.urlshortener.repository.CampaignRepository;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.ReportedDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.specification.LikePatterns;
import cc.hrva.urlshortener.repository.specification.UrlSpecification;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.ShapeHeld;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import cc.hrva.urlshortener.service.campaign.UrlShapes;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.SortWhitelist;
import com.google.common.collect.Lists;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCampaignService implements CampaignService {

    private static final String SHAPE_KEY_PREFIX = "campaign:shape:";
    private static final String MEMBER_SEPARATOR = "|";
    private static final int MAX_EXAMPLES = 3;
    private static final int IDS_PER_QUERY = 1_000;
    private static final Duration LISTED_FOR = Duration.ofDays(90);
    private static final String MATCH_ALL = "%";
    private static final String ID = "id";
    private static final String LAST_SEEN_AT = "lastSeenAt";
    private static final String CREATE_DATE = "createDate";
    private static final SortWhitelist CAMPAIGN_SORTS = SortWhitelist.of(LAST_SEEN_AT, "firstSeenAt", "status")
            .byDefault(Sort.by(Sort.Order.desc(LAST_SEEN_AT)))
            .thenBy(Sort.Order.desc(LAST_SEEN_AT), Sort.Order.desc(ID));
    private static final SortWhitelist LINK_SORTS = SortWhitelist.of(CREATE_DATE, ID)
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(CREATE_DATE), Sort.Order.desc(ID));
    private static final String TARGET_CAMPAIGN = "Campaign";

    private final RedisBurstCounter redisBurstCounter;
    private final UrlRepository urlRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignLinkRepository campaignLinkRepository;
    private final ModerationService moderationService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final SettingsService settingsService;
    private final LinkStatsRepository linkStatsRepository;
    private final ReportedDomainRepository reportedDomainRepository;
    private final AuditLogService auditLogService;
    private final AnalyticsDays analyticsDays;

    @Override
    public Optional<Long> holdingCampaignId(final UrlShape shape) {
        if (!shape.holdable() || !holdsNewLinks()) {
            return Optional.empty();
        }
        final var now = now();
        return campaignRepository.findByShapeKey(shape.key()).filter(campaign -> campaign.isHoldingAt(now)).map(Campaign::getId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addHeldLink(final long campaignId, final long urlId, final String domain) {
        final var now = now();
        campaignLinkRepository.saveAndFlush(campaignLink(new CampaignLinkId(campaignId, urlId), domain, now));
        campaignRepository.extendHold(campaignId, now, holdEnd(now));
    }

    @Async
    @Override
    public void recordAnonymousLink(final long urlId, final String longUrl, final LinkVerdict.Outcome outcome) {
        if (!settingsService.isOn(SettingKey.SAME_SHAPE_ALERT) || outcome == LinkVerdict.Outcome.ALLOWED) {
            return;
        }
        final var host = DomainNames.hostOf(longUrl).filter(candidate -> !DomainNames.isIpLiteral(candidate));
        final var shape = UrlShapes.of(longUrl);
        if (host.isEmpty() || shape.isEmpty()) {
            return;
        }
        try {
            record(urlId, shape.get(), DomainNames.registrableDomain(host.get()));
        } catch (final RuntimeException exception) {
            log.warn("Campaign check skipped shape={} exception={}", shape.get().key(), exception.getClass().getName());
        }
    }

    @Override
    public Page<CampaignResponse> campaigns(final String search, final Pageable pageable) {
        final var pattern = StringUtils.isBlank(search) ? MATCH_ALL : LikePatterns.containing(search.strip());
        final var page = campaignRepository.findRecent(now().minus(LISTED_FOR), pattern, CAMPAIGN_SORTS.apply(pageable));
        final var responses = responsesOf(page.getContent());
        return page.map(campaign -> responses.get(campaign.getId()));
    }

    @Override
    public CampaignDetailResponse detail(final long id) {
        final var campaign = campaign(id);
        final var domains = campaignLinkRepository.countDomainsByCampaignId(id);
        final var reports = reportsOf(domains.stream().map(CampaignDomainCount::domain).toList());
        return new CampaignDetailResponse(responsesOf(List.of(campaign)).get(id), domains.stream()
                .map(domain -> new CampaignDomainResponse(domain.domain(), domain.links(),
                        Optional.ofNullable(reports.get(domain.domain())).map(ReportStatus::from).orElse(null)))
                .toList());
    }

    @Override
    public Page<CampaignLinkResponse> links(final long id, final String search, final Pageable pageable) {
        campaign(id);
        var specification = UrlSpecification.inCampaign(id);
        if (StringUtils.isNotBlank(search)) {
            specification = specification.and(UrlSpecification.search(search.strip()));
        }
        final var page = urlRepository.findAll(specification, LINK_SORTS.apply(pageable));
        final var urlIds = page.getContent().stream().map(Url::getId).toList();
        if (urlIds.isEmpty()) {
            return new PageImpl<>(List.of(), page.getPageable(), page.getTotalElements());
        }
        final var domains = campaignLinkRepository.findByIdCampaignIdAndIdUrlIdIn(id, urlIds).stream()
                .collect(Collectors.toMap(link -> link.getId().urlId(), CampaignLink::getDomain, (first, second) -> first));
        final var clicks = clicksByUrl(urlIds, earliest(page.getContent().stream().map(Url::getCreateDate).toList()));
        return page.map(url -> new CampaignLinkResponse(url.getId(), url.getShortUrl(), url.getLongUrl(), domains.get(url.getId()), url.getCreateDate(),
                url.isActive(), url.getBlockReason(), clicks.getOrDefault(url.getId(), 0L)));
    }

    @Override
    public int allow(final long id, final String performedBy) {
        final var campaign = campaign(id);
        if (campaignRepository.allow(id, performedBy, now()) == 1) {
            log.info("Campaign shape allowed campaignId={}", id);
        }
        var restored = 0;
        for (final var chunk : Lists.partition(linkIdsOf(id), IDS_PER_QUERY)) {
            restored += moderationService.restoreCampaignHolds(chunk);
        }
        auditLogService.log(performedBy, AuditAction.ALLOW_CAMPAIGN_SHAPE, TARGET_CAMPAIGN, String.valueOf(id),
                "shape: " + campaign.shape().display() + ", links: " + restored);
        return restored;
    }

    @Override
    public List<DomainBlockPreviewResponse> blockPreview(final long id) {
        campaign(id);
        return moderationService.previewDomainBlocks(linkIdsOf(id));
    }

    @Override
    public List<Long> linkIds(final long id) {
        campaign(id);
        return linkIdsOf(id);
    }

    private Campaign campaign(final long id) {
        return campaignRepository.findById(id).orElseThrow(() -> new CampaignNotFoundException("There is no campaign %d.".formatted(id)));
    }

    private List<Long> linkIdsOf(final long id) {
        return campaignLinkRepository.findByIdCampaignId(id).stream().map(link -> link.getId().urlId()).sorted().toList();
    }

    private Map<Long, CampaignResponse> responsesOf(final List<Campaign> campaigns) {
        if (campaigns.isEmpty()) {
            return Map.of();
        }
        final var ids = campaigns.stream().map(Campaign::getId).toList();
        final var counts = campaignLinkRepository.countByCampaignIdIn(ids).stream()
                .collect(Collectors.toMap(CampaignLinkCounts::campaignId, Function.identity()));
        final var reports = campaignLinkRepository.countReportsByCampaignIdIn(ids).stream()
                .collect(Collectors.toMap(CampaignReportCounts::campaignId, Function.identity()));
        final var clicks = clicksByCampaign(ids, earliest(counts.values().stream().map(CampaignLinkCounts::firstLinkCreated).toList()));
        final var now = now();
        final var holdsNewLinks = holdsNewLinks();
        return campaigns.stream().collect(Collectors.toMap(Campaign::getId, campaign -> response(campaign, now, holdsNewLinks,
                Optional.ofNullable(counts.get(campaign.getId())), Optional.ofNullable(reports.get(campaign.getId())),
                clicks.getOrDefault(campaign.getId(), 0L))));
    }

    private static CampaignResponse response(final Campaign campaign, final LocalDateTime now, final boolean holdsNewLinks,
                                             final Optional<CampaignLinkCounts> counts, final Optional<CampaignReportCounts> reports, final long clicks) {
        final var holdingRow = campaign.isHoldingAt(now);
        return new CampaignResponse(campaign.getId(), campaign.shape().display(), campaign.getPath(), campaign.getQueryNames(), campaign.getStatus(),
                holdingRow && holdsNewLinks, holdingRow && !holdsNewLinks ? CampaignResponse.HOLDS_TURNED_OFF : null, campaign.getFirstSeenAt(),
                campaign.getLastSeenAt(), campaign.getActiveUntil(), campaign.getAllowedBy(), campaign.getAllowedAt(),
                counts.map(count -> new CampaignResponse.Links(count.total(), count.active(), count.held(), count.blocked()))
                        .orElseGet(() -> new CampaignResponse.Links(0, 0, 0, 0)),
                counts.map(CampaignLinkCounts::domains).orElse(0L),
                clicks,
                reports.map(report -> new CampaignResponse.Reports(report.reported(), report.suspended()))
                        .orElseGet(() -> new CampaignResponse.Reports(0, 0)));
    }

    private Map<Long, Long> clicksByCampaign(final Collection<Long> campaignIds, final Optional<LocalDateTime> firstLinkCreated) {
        return firstLinkCreated
                .map(first -> linkStatsRepository.sumClicksByCampaign(campaignIds, analyticsDate(first), analyticsDays.today()))
                .orElseGet(Map::of);
    }

    private Map<Long, Long> clicksByUrl(final List<Long> urlIds, final Optional<LocalDateTime> firstLinkCreated) {
        if (urlIds.isEmpty() || firstLinkCreated.isEmpty()) {
            return Map.of();
        }
        final var from = analyticsDate(firstLinkCreated.get());
        final var to = analyticsDays.today();
        final var clicks = new HashMap<Long, Long>();
        for (final var chunk : Lists.partition(urlIds, IDS_PER_QUERY)) {
            clicks.putAll(linkStatsRepository.sumClicksByUrl(chunk, from, to));
        }
        return clicks;
    }

    private LocalDate analyticsDate(final LocalDateTime stored) {
        return stored.atZone(clock.getZone()).withZoneSameInstant(analyticsDays.zone()).toLocalDate();
    }

    private Map<String, ReportedDomain> reportsOf(final List<String> domains) {
        final var reports = new HashMap<String, ReportedDomain>();
        for (final var chunk : Lists.partition(domains, IDS_PER_QUERY)) {
            reportedDomainRepository.findAllById(chunk).forEach(report -> reports.put(report.getDomain(), report));
        }
        return reports;
    }

    private static Optional<LocalDateTime> earliest(final List<LocalDateTime> dates) {
        return dates.stream().filter(Objects::nonNull).min(Comparator.naturalOrder());
    }

    private void record(final long urlId, final UrlShape shape, final String domain) {
        final var window = Duration.ofHours(settingsService.number(SettingKey.CAMPAIGN_WINDOW_HOURS));
        final var members = redisBurstCounter.recordUnderKey(SHAPE_KEY_PREFIX + shape.key(), urlId + MEMBER_SEPARATOR + domain, clock.instant(), window);
        final var now = now();
        final var existing = campaignRepository.findByShapeKey(shape.key());
        if (existing.isPresent() && (existing.get().isActiveAt(now) || existing.get().getStatus() == CampaignStatus.ALLOWED)) {
            addMatch(existing.get(), shape, urlId, domain, now);
            return;
        }
        final var links = linksOf(members);
        final var domains = domainsOf(members);
        if (links.size() < settingsService.number(SettingKey.CAMPAIGN_LINKS) || domains < settingsService.number(SettingKey.CAMPAIGN_DOMAINS)) {
            return;
        }
        final var status = holdsNewLinks() && shape.holdable() ? CampaignStatus.HOLDING : CampaignStatus.ALERT_ONLY;
        final var crossing = new Crossing(shape, links, domains, window, now, status, holdEnd(now));
        final var opened = existing.isPresent() ? reopen(existing.get(), crossing) : open(crossing);
        if (opened.isEmpty()) {
            campaignRepository.findByShapeKey(shape.key()).ifPresent(campaign -> addMatch(campaign, shape, urlId, domain, now));
            return;
        }
        holdAndAlert(opened.get(), crossing);
    }

    private void addMatch(final Campaign campaign, final UrlShape shape, final long urlId, final String domain, final LocalDateTime now) {
        if (!addLink(campaign.getId(), urlId, domain, now)) {
            return;
        }
        if (campaign.getStatus() == CampaignStatus.ALLOWED) {
            campaignRepository.markSeen(campaign.getId(), now);
            return;
        }
        campaignRepository.extendHold(campaign.getId(), now, holdEnd(now));
        if (shape.holdable() && holdsNewLinks() && isHolding(campaign)) {
            hold(campaign.getId(), urlId, shape);
        }
    }

    private boolean isHolding(final Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.ALERT_ONLY) {
            return campaign.getStatus() == CampaignStatus.HOLDING;
        }
        if (campaignRepository.startHolding(campaign.getId()) == 1) {
            log.info("Campaign started holding campaignId={}", campaign.getId());
            return true;
        }
        return campaignRepository.findById(campaign.getId()).map(Campaign::getStatus).filter(CampaignStatus.HOLDING::equals).isPresent();
    }

    private Optional<Campaign> open(final Crossing crossing) {
        final var campaign = Campaign.builder()
                .shapeKey(crossing.shape().key())
                .path(crossing.shape().path())
                .queryNames(crossing.shape().queryNames())
                .status(crossing.status())
                .firstSeenAt(crossing.now())
                .lastSeenAt(crossing.now())
                .activeUntil(crossing.holdEnd())
                .alertedAt(crossing.now())
                .build();
        try {
            return Optional.ofNullable(transactionOperations.execute(status -> campaignRepository.saveAndFlush(campaign)));
        } catch (final DataIntegrityViolationException exception) {
            log.info("Campaign opened meanwhile shape={}", crossing.shape().key());
            return Optional.empty();
        }
    }

    private Optional<Campaign> reopen(final Campaign campaign, final Crossing crossing) {
        final var changed = transactionOperations.execute(status -> campaignRepository.reopen(campaign.getId(), crossing.status(), crossing.now(),
                crossing.holdEnd()));
        if (changed == null || changed == 0) {
            log.info("Campaign reopened meanwhile campaignId={}", campaign.getId());
            return Optional.empty();
        }
        return Optional.of(campaign);
    }

    private void holdAndAlert(final Campaign campaign, final Crossing crossing) {
        final var holding = crossing.status() == CampaignStatus.HOLDING;
        crossing.links().forEach((urlId, domain) -> {
            if (addLink(campaign.getId(), urlId, domain, crossing.now()) && holding) {
                hold(campaign.getId(), urlId, crossing.shape());
            }
        });
        final var shape = crossing.shape();
        eventPublisher.publishEvent(new ShapeHeld(campaign.getId(), shape.display(), shape.path(), crossing.links().size(), crossing.domains(),
                crossing.window(), examplesOf(shape, List.copyOf(crossing.links().keySet())), holding, holdDays()));
        log.info("Campaign alert shape={} display={} campaignId={} status={} links={} domains={}", shape.key(), shape.display(), campaign.getId(),
                crossing.status(), crossing.links().size(), crossing.domains());
    }

    private boolean addLink(final long campaignId, final long urlId, final String domain, final LocalDateTime now) {
        final var id = new CampaignLinkId(campaignId, urlId);
        try {
            transactionOperations.executeWithoutResult(status -> {
                if (!campaignLinkRepository.existsById(id)) {
                    campaignLinkRepository.saveAndFlush(campaignLink(id, domain, now));
                }
            });
            return true;
        } catch (final DataIntegrityViolationException exception) {
            return addedMeanwhile(id);
        } catch (final RuntimeException exception) {
            log.warn("Campaign link failed campaignId={} urlId={} exception={}", campaignId, urlId, exception.getClass().getName());
            return false;
        }
    }

    private boolean addedMeanwhile(final CampaignLinkId id) {
        try {
            if (campaignLinkRepository.existsById(id)) {
                return true;
            }
            log.info("Campaign link skipped campaignId={} urlId={}", id.campaignId(), id.urlId());
        } catch (final RuntimeException exception) {
            log.warn("Campaign link failed campaignId={} urlId={} exception={}", id.campaignId(), id.urlId(), exception.getClass().getName());
        }
        return false;
    }

    private void hold(final long campaignId, final long urlId, final UrlShape shape) {
        try {
            moderationService.holdForCampaign(urlId, campaignId, shape.display());
        } catch (final RuntimeException exception) {
            log.warn("Campaign hold failed campaignId={} urlId={} exception={}", campaignId, urlId, exception.getClass().getName());
        }
    }

    private boolean holdsNewLinks() {
        return settingsService.isOn(SettingKey.SAME_SHAPE_ALERT) && settingsService.isOn(SettingKey.CAMPAIGN_HOLD);
    }

    private int holdDays() {
        return settingsService.number(SettingKey.CAMPAIGN_HOLD_DAYS);
    }

    private LocalDateTime holdEnd(final LocalDateTime now) {
        return now.plusDays(holdDays());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private List<String> examplesOf(final UrlShape shape, final List<Long> urlIds) {
        try {
            return newestAliases(urlIds);
        } catch (final RuntimeException exception) {
            log.warn("Campaign examples skipped shape={} exception={}", shape.key(), exception.getClass().getName());
            return List.of();
        }
    }

    private List<String> newestAliases(final List<Long> urlIds) {
        final var newest = urlIds.subList(Math.max(0, urlIds.size() - MAX_EXAMPLES), urlIds.size()).reversed();
        final var aliases = urlRepository.findAllById(newest).stream().collect(Collectors.toMap(Url::getId, Url::getShortUrl, (first, second) -> first));
        return newest.stream().filter(aliases::containsKey).map(aliases::get).toList();
    }

    private static CampaignLink campaignLink(final CampaignLinkId id, final String domain, final LocalDateTime now) {
        return CampaignLink.builder().id(id).domain(domain).createdAt(now).build();
    }

    private static Map<Long, String> linksOf(final List<String> members) {
        return members.stream().collect(Collectors.toMap(member -> Long.parseLong(StringUtils.substringBefore(member, MEMBER_SEPARATOR)),
                member -> StringUtils.substringAfter(member, MEMBER_SEPARATOR), (first, second) -> first, LinkedHashMap::new));
    }

    private static int domainsOf(final List<String> members) {
        return (int) members.stream().map(member -> StringUtils.substringAfter(member, MEMBER_SEPARATOR)).distinct().count();
    }

    private record Crossing(UrlShape shape, Map<Long, String> links, int domains, Duration window, LocalDateTime now, CampaignStatus status,
                            LocalDateTime holdEnd) {}

}
