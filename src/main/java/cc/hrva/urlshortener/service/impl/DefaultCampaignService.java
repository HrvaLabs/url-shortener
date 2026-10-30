package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.Campaign;
import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.CampaignLinkId;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import cc.hrva.urlshortener.repository.CampaignLinkRepository;
import cc.hrva.urlshortener.repository.CampaignRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.ShapeHeld;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import cc.hrva.urlshortener.service.campaign.UrlShapes;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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

    private final RedisBurstCounter redisBurstCounter;
    private final UrlRepository urlRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignLinkRepository campaignLinkRepository;
    private final ModerationService moderationService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final SettingsService settingsService;

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
        if (campaign.getStatus() == CampaignStatus.HOLDING && shape.holdable() && holdsNewLinks()) {
            hold(campaign.getId(), urlId, shape);
        }
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
            if (campaignLinkRepository.existsById(id)) {
                return true;
            }
            log.info("Campaign link skipped campaignId={} urlId={}", campaignId, urlId);
            return false;
        }
    }

    private void hold(final long campaignId, final long urlId, final UrlShape shape) {
        try {
            moderationService.holdForCampaign(urlId, shape.display());
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
