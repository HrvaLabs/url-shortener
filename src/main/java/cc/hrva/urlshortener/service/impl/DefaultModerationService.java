package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.DomainBulkBlockResponse;
import cc.hrva.urlshortener.dto.DomainRuleCreatedResponse;
import cc.hrva.urlshortener.dto.DomainRuleDto;
import cc.hrva.urlshortener.dto.DomainRuleResponse;
import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.ReviewBulkResolveResponse;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.BulkBlockIncompleteException;
import cc.hrva.urlshortener.exception.CommonException;
import cc.hrva.urlshortener.exception.DomainRuleNotFoundException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.ReviewAction;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.model.enums.ThreatType;
import cc.hrva.urlshortener.repository.CampaignRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.specification.DomainRuleSpecification;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.blocklist.BlocklistConfigurationChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainModeration;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.domain.UrlCacheKeys;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.SortWhitelist;
import com.google.common.collect.Lists;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DefaultModerationService implements ModerationService {

    private static final int PAGE_SIZE = 500;
    private static final Pageable FIRST_PAGE = PageRequest.ofSize(PAGE_SIZE);
    private static final String DETAIL_SEPARATOR = ", ";
    private static final String TARGET_URL = "Url";
    private static final String TARGET_DOMAIN = "Domain";
    private static final String TARGET_USER = "User";
    private static final String ACTION = "action";
    private static final String DOMAIN = "domain";
    private static final String REASON = "reason";
    private static final String DOMAINS = "domains";
    private static final String DOMAINS_FAILED = "failed to turn off";
    private static final String HELD_LINKS = "held links";
    private static final String WARNING_PAGES_CLEARED = "warning pages cleared";
    private static final String NOT_A_DOMAIN = "Enter a domain such as example.com, not a top-level domain or an IP address.";
    private static final String OVER_THE_CAP = "This would affect %d links, more than the %d allowed here. Use the dashboard.";
    private static final String NOT_COVERED = "%s doesn't cover this link's destination %s.";
    private static final String NO_OWNER = "This link has no owner to block.";
    private static final String ADMIN_ACCOUNT = "Admin accounts can't be blocked here.";
    private static final String NOT_IN_QUEUE = "This link is no longer in the review queue.";
    private static final String RESOLVE_FAILED = "Resolving this link failed. Try again.";
    private static final String BLOCK_FAILED = "Blocking this domain failed. Try again.";
    private static final String BULK_BLOCK_INCOMPLETE = "The domains are blocked, but turning off their links failed. Block the same domains again to finish.";
    private static final String SCAN_FAILED = "scan failed";
    private static final String ALLOWED_BY_A_RULE = "Allowed by a rule";
    private static final UnaryOperator<String> TRUSTED_BY_DEFAULT = UnaryOperator.identity();
    private static final UnaryOperator<String> BLOCKED_BY_DEFAULT = DomainNames::registrableDomain;
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String SYSTEM = "system";
    private static final String NO_RULE_TYPE = "Choose whether the rule blocks or allows the domain";
    private static final String CREATED_AT = "createdAt";
    private static final String ID = "id";
    private static final SortWhitelist RULE_SORTS = SortWhitelist.of("domain", "type", CREATED_AT)
            .byDefault(Sort.by(Sort.Order.desc(CREATED_AT)))
            .thenBy(Sort.Order.desc(CREATED_AT), Sort.Order.desc(ID));
    private static final Set<BlockReason> RESTORED_BY_ALLOW = EnumSet.of(BlockReason.BLOCKLIST, BlockReason.SCAM_SIGNALS, BlockReason.DOMAIN_RULE,
            BlockReason.CAMPAIGN);
    private static final Set<BlockReason> HELD = EnumSet.of(BlockReason.CAMPAIGN);
    private static final int IDS_PER_QUERY = 1_000;
    private static final Duration SECOND_EVICTION_DELAY = Duration.ofSeconds(2);
    private static final int MAX_COLUMN_LENGTH = 255;

    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final DomainRuleRepository domainRuleRepository;
    private final LinkCheckService linkCheckService;
    private final ReviewQueueService reviewQueueService;
    private final UserService userService;
    private final SendingEmailService sendingEmailService;
    private final AuditLogService auditLogService;
    private final UrlCache urlCache;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final ScammerSignalService scammerSignalService;
    private final TaskScheduler taskScheduler;
    private final CustomDomainModeration customDomainModeration;
    private final CampaignRepository campaignRepository;

    @Override
    public UrlResponse blockLink(final long urlId, final BlockReason reason, final String detail, final String performedBy, final String via) {
        final var url = findUrl(urlId);
        final var blocked = blockAndTellOwner(urlId, reason, detail, ReviewResolution.LINK_BLOCKED, performedBy, candidate -> true,
                this::notifyOwner);
        auditLogService.log(performedBy, AuditAction.BLOCK_LINK, TARGET_URL, String.valueOf(urlId),
                details(via, blocked.isPresent() ? 1 : 0, field(REASON, reason), field("detail", detail)));
        return UrlResponse.from(blocked.orElse(url));
    }

    @Override
    public int blockDomain(final String domain, final String reason, final String performedBy, final String via, final Integer maxLinks) {
        return blockDomainRule(domain, reason, performedBy, via, maxLinks).links();
    }

    private Affected blockDomainRule(final String domain, final String reason, final String performedBy, final String via, final Integer maxLinks) {
        final var ruleDomain = ruleDomain(domain);
        final var blocked = blockLinksOn(ruleDomain, reason, performedBy, maxLinks);
        logDomainBlock(ruleDomain, blocked.links(), domainsField(blocked.domains()), blocked.held(), reason, performedBy, via);
        return blocked;
    }

    private void logDomainBlock(final String ruleDomain, final int blocked, final String domains, final int held, final String reason,
                                final String performedBy, final String via) {
        auditLogService.log(performedBy, AuditAction.BLOCK_DOMAIN, TARGET_DOMAIN, ruleDomain,
                details(via, blocked, field(REASON, reason), domains, heldField(held)));
    }

    @Override
    public List<DomainBlockPreviewResponse> previewDomainBlocks(final List<Long> urlIds) {
        final var hostsByDomain = new TreeMap<String, List<String>>();
        for (final var chunk : Lists.partition(urlIds, IDS_PER_QUERY)) {
            for (final var destination : urlRepository.findDestinationsByIdIn(chunk)) {
                DomainNames.hostOf(destination.longUrl()).ifPresent(host -> ruleDomainOf(DomainNames.registrableDomain(host))
                        .ifPresent(domain -> hostsByDomain.computeIfAbsent(domain, key -> new ArrayList<>()).add(host)));
            }
        }
        if (hostsByDomain.isEmpty()) {
            return List.of();
        }
        final var rules = domainRuleRepository.findAll();
        final var activeLinks = activeLinkIdsBlockedBy(hostsByDomain.keySet(), rules);
        final var heldLinks = heldLinkIdsBlockedBy(hostsByDomain.keySet(), rules);
        return hostsByDomain.entrySet().stream()
                .map(entry -> new DomainBlockPreviewResponse(entry.getKey(), entry.getValue().stream().distinct().sorted().toList(),
                        entry.getValue().size(), activeLinks.getOrDefault(entry.getKey(), List.of()).size(),
                        heldLinks.getOrDefault(entry.getKey(), List.of()).size(), ruleTypeOf(entry.getKey(), rules)))
                .toList();
    }

    @Override
    public DomainBulkBlockResponse blockDomains(final List<String> domains, final String reason, final String performedBy, final String via) {
        final var skipped = new ArrayList<DomainBulkBlockResponse.Skipped>();
        final var saved = saveBlockRules(domainsToBlock(domains, skipped), reason, performedBy, skipped);
        final var blocked = new ArrayList<DomainBulkBlockResponse.Blocked>();
        final var linksBlocked = new ArrayList<Url>();
        if (!saved.isEmpty()) {
            final var scan = scanAfterSaving(saved, reason, performedBy, via);
            for (final var ruleDomain : saved) {
                final var domainsDisabled = disableMatchingDomains(ruleDomain, reason);
                final var rulesBelow = rulesBelow(ruleDomain, scan.rules());
                final var count = eachLink(scan.idsByDomain().getOrDefault(ruleDomain, List.of()), Long::longValue,
                        id -> blockOnDomain(id, ruleDomain, rulesBelow, performedBy, linksBlocked::add));
                final var held = convertHeld(scan.heldIdsByDomain().getOrDefault(ruleDomain, List.of()), ruleDomain, rulesBelow);
                blocked.add(new DomainBulkBlockResponse.Blocked(ruleDomain, count, domainsDisabled.orElse(0), held));
                logBulkDomainBlock(ruleDomain, count, domainsDisabled, held, reason, performedBy, via);
            }
        }
        notifyOwners(linksBlocked);
        final var linksDeactivated = blocked.stream().mapToInt(DomainBulkBlockResponse.Blocked::linksDeactivated).sum();
        final var heldLinksConverted = blocked.stream().mapToInt(DomainBulkBlockResponse.Blocked::heldLinksConverted).sum();
        log.info("Domains blocked in bulk blocked={} skipped={} links={} held={}", blocked.size(), skipped.size(), linksDeactivated,
                heldLinksConverted);
        return new DomainBulkBlockResponse(blocked, skipped, linksDeactivated, heldLinksConverted);
    }

    private Map<String, String> domainsToBlock(final List<String> domains, final List<DomainBulkBlockResponse.Skipped> skipped) {
        final var seen = new HashSet<String>();
        final var toBlock = new LinkedHashMap<String, String>();
        for (final var domain : domains) {
            final var sent = StringUtils.strip(domain);
            try {
                final var ruleDomain = ruleDomain(domain);
                if (!seen.add(ruleDomain)) {
                    continue;
                }
                if (isAllowedByItsRule(ruleDomain)) {
                    skipped.add(new DomainBulkBlockResponse.Skipped(sent, ALLOWED_BY_A_RULE));
                } else {
                    toBlock.put(ruleDomain, sent);
                }
            } catch (final CommonException exception) {
                skipped.add(new DomainBulkBlockResponse.Skipped(sent, exception.getMessage()));
            } catch (final RuntimeException exception) {
                log.warn("Checking a domain to block failed, continuing with the next one domain={}", sent, exception);
                skipped.add(new DomainBulkBlockResponse.Skipped(sent, BLOCK_FAILED));
            }
        }
        return toBlock;
    }

    private List<String> saveBlockRules(final Map<String, String> toBlock, final String reason, final String performedBy,
                                        final List<DomainBulkBlockResponse.Skipped> skipped) {
        final var saved = new ArrayList<String>();
        toBlock.forEach((ruleDomain, sent) -> {
            try {
                saveRule(ruleDomain, DomainRuleType.BLOCK, reason, performedBy);
                saved.add(ruleDomain);
            } catch (final RuntimeException exception) {
                log.warn("Blocking a domain failed, continuing with the next one domain={}", ruleDomain, exception);
                skipped.add(new DomainBulkBlockResponse.Skipped(sent, BLOCK_FAILED));
            }
        });
        return saved;
    }

    private Scan scanAfterSaving(final List<String> saved, final String reason, final String performedBy, final String via) {
        try {
            final var rules = domainRuleRepository.findAll();
            return new Scan(rules, activeLinkIdsBlockedBy(saved, rules), heldLinkIdsBlockedBy(saved, rules));
        } catch (final RuntimeException exception) {
            log.error("Reading the active links of a bulk domain block failed after its rules were saved domains={}", saved.size(), exception);
            saved.forEach(ruleDomain -> logScanFailure(ruleDomain, reason, performedBy, via));
            throw new BulkBlockIncompleteException(BULK_BLOCK_INCOMPLETE, exception);
        }
    }

    private void logScanFailure(final String ruleDomain, final String reason, final String performedBy, final String via) {
        final var details = Stream.of(field(REASON, reason), field("via", via), SCAN_FAILED)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(DETAIL_SEPARATOR));
        try {
            auditLogService.logFailure(performedBy, AuditAction.BLOCK_DOMAIN, TARGET_DOMAIN, ruleDomain, details);
        } catch (final RuntimeException exception) {
            log.warn("Writing the failure entry of a blocked domain failed domain={}", ruleDomain, exception);
        }
    }

    private OptionalInt disableMatchingDomains(final String ruleDomain, final String reason) {
        try {
            return OptionalInt.of(customDomainModeration.disableMatching(ruleDomain, reason));
        } catch (final RuntimeException exception) {
            log.warn("Turning off the custom domains of a blocked domain failed, continuing domain={}", ruleDomain, exception);
            return OptionalInt.empty();
        }
    }

    private void logBulkDomainBlock(final String ruleDomain, final int blocked, final OptionalInt domains, final int held, final String reason,
                                    final String performedBy, final String via) {
        try {
            logDomainBlock(ruleDomain, blocked, domains.isPresent() ? domainsField(domains.getAsInt()) : field(DOMAINS, DOMAINS_FAILED), held, reason,
                    performedBy, via);
        } catch (final RuntimeException exception) {
            log.warn("Writing the audit entry of a blocked domain failed domain={} links={}", ruleDomain, blocked, exception);
        }
    }

    @Override
    public int allowDomain(final String domain, final String reason, final String performedBy, final String via) {
        final var ruleDomain = ruleDomain(domain);
        final var trusted = trust(ruleDomain, reason, performedBy);
        auditLogService.log(performedBy, AuditAction.ALLOW_DOMAIN, TARGET_DOMAIN, ruleDomain,
                details(via, trusted.links(), field(REASON, reason), field("reviews", trusted.reviews()), warningPagesField(trusted.warningPages())));
        return trusted.links();
    }

    @Override
    public DomainRuleCreatedResponse createDomainRule(final DomainRuleDto domainRuleDto, final String performedBy, final String via) {
        final var type = Optional.ofNullable(domainRuleDto.type()).orElseThrow(() -> new UrlValidationException(NO_RULE_TYPE));
        final var ruleDomain = ruleDomain(domainRuleDto.domain());
        final var affected = switch (type) {
            case BLOCK -> blockDomainRule(ruleDomain, domainRuleDto.reason(), performedBy, via, null);
            case ALLOW -> new Affected(allowDomain(ruleDomain, domainRuleDto.reason(), performedBy, via), 0, 0);
        };
        final var rule = domainRuleRepository.findByDomain(ruleDomain)
                .orElseThrow(() -> new DomainRuleNotFoundException("There is no rule for %s.".formatted(ruleDomain)));
        return new DomainRuleCreatedResponse(DomainRuleResponse.from(rule), affected.links(), affected.domains());
    }

    @Override
    public Page<DomainRuleResponse> domainRules(final DomainRuleType type, final String search, final Pageable pageable) {
        var spec = Specification.<DomainRule>unrestricted();
        if (type != null) {
            spec = spec.and(DomainRuleSpecification.hasType(type));
        }
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(DomainRuleSpecification.searchMatches(search.strip()));
        }
        return domainRuleRepository.findAll(spec, RULE_SORTS.apply(pageable)).map(DomainRuleResponse::from);
    }

    @Override
    public void removeDomainRule(final String domain, final String performedBy, final String via) {
        final var ruleDomain = DomainNames.hostOf(StringUtils.strip(domain)).orElseThrow(() -> new UrlValidationException(NOT_A_DOMAIN));
        final var removedType = transactionOperations.execute(status -> {
            final var rule = domainRuleRepository.findByDomain(ruleDomain)
                    .orElseThrow(() -> new DomainRuleNotFoundException("There is no rule for %s.".formatted(ruleDomain)));
            domainRuleRepository.delete(rule);
            return rule.getType();
        });
        log.info("Domain rule removed domain={} type={}", ruleDomain, removedType);
        eventPublisher.publishEvent(new BlocklistConfigurationChangedEvent());
        auditLogService.log(performedBy, AuditAction.REMOVE_DOMAIN_RULE, TARGET_DOMAIN, ruleDomain, details(via, 0, field("type", removedType)));
    }

    @Override
    public void removeDomainRule(final long ruleId, final String performedBy, final String via) {
        final var rule = domainRuleRepository.findById(ruleId)
                .orElseThrow(() -> new DomainRuleNotFoundException("There is no rule with id %d.".formatted(ruleId)));
        removeDomainRule(rule.getDomain(), performedBy, via);
    }

    @Override
    public int blockUser(final long userId, final String performedBy, final String via, final Integer maxLinks) {
        final var user = findUser(userId);
        final var blocked = blockLinksOf(user, performedBy, maxLinks);
        auditLogService.log(performedBy, AuditAction.BLOCK_USER, TARGET_USER, String.valueOf(userId),
                details(via, blocked.links(), domainsField(blocked.domains())));
        return blocked.links();
    }

    @Override
    public int reactivateUser(final long userId, final String performedBy, final String via) {
        final var user = findUser(userId);
        if (userService.reactivate(userId)) {
            log.info("User reactivated id={}", userId);
        }
        final var domains = customDomainModeration.enableForUser(userId);
        final var restored = eachLink(urlRepository.findIdsByOwnerAndBlockReason(user, BlockReason.USER_BLOCKED), Long::longValue,
                id -> reactivate(id, url -> url.getBlockReason() == BlockReason.USER_BLOCKED && url.getThreatType() == null));
        scammerSignalService.invalidate();
        auditLogService.log(performedBy, AuditAction.REACTIVATE_USER, TARGET_USER, String.valueOf(userId),
                details(via, restored, domainsField(domains)));
        return restored;
    }

    @Override
    public void resolveReview(final long urlId, final ReviewAction action, final String domain, final String performedBy, final String via) {
        resolveReview(urlId, action, domain, this::notifyOwner, performedBy, via);
    }

    private void resolveReview(final long urlId, final ReviewAction action, final String domain, final Consumer<Url> tellOwner,
                               final String performedBy, final String via) {
        final var url = findUrl(urlId);
        final var details = switch (action) {
            case OK -> details(via, 0, field(ACTION, action));
            case TRUST_DOMAIN -> trustFromReview(url, domain, performedBy, via);
            case BLOCK_LINK -> blockLinkFromReview(url, tellOwner, performedBy, via);
            case BLOCK_DOMAIN -> blockDomainFromReview(url, domain, performedBy, via);
            case BLOCK_USER -> blockUserFromReview(url, performedBy, via);
        };
        closeReview(url, action, details, performedBy);
    }

    @Override
    public ReviewBulkResolveResponse resolveReviews(final List<Long> urlIds, final ReviewAction action, final String performedBy, final String via) {
        final var open = reviewQueueService.openUrlIds(urlIds);
        final var domainsDone = new ArrayList<String>();
        final var linksBlocked = new ArrayList<Url>();
        final var resolved = new ArrayList<Long>();
        final var failed = new ArrayList<ReviewBulkResolveResponse.Failure>();
        for (final var urlId : urlIds) {
            if (!open.contains(urlId)) {
                failed.add(new ReviewBulkResolveResponse.Failure(urlId, NOT_IN_QUEUE));
                continue;
            }
            try {
                resolveInBulk(urlId, action, domainsDone, linksBlocked::add, performedBy, via);
                resolved.add(urlId);
            } catch (final CommonException exception) {
                failed.add(new ReviewBulkResolveResponse.Failure(urlId, exception.getMessage()));
            } catch (final RuntimeException exception) {
                log.warn("Resolving a review failed, continuing with the next one urlId={}", urlId, exception);
                failed.add(new ReviewBulkResolveResponse.Failure(urlId, RESOLVE_FAILED));
            }
        }
        notifyOwners(linksBlocked);
        log.info("Reviews resolved in bulk action={} resolved={} failed={}", action, resolved.size(), failed.size());
        return new ReviewBulkResolveResponse(resolved, failed);
    }

    private void resolveInBulk(final long urlId, final ReviewAction action, final List<String> domainsDone, final Consumer<Url> tellOwner,
                               final String performedBy, final String via) {
        if (action != ReviewAction.TRUST_DOMAIN && action != ReviewAction.BLOCK_DOMAIN) {
            resolveReview(urlId, action, null, tellOwner, performedBy, via);
            return;
        }
        final var url = findUrl(urlId);
        final var doneDomain = DomainNames.hostOf(url.getLongUrl())
                .flatMap(host -> domainsDone.stream().filter(domain -> covers(host, domain)).findFirst());
        if (doneDomain.isPresent()) {
            closeReview(url, action, details(via, 0, field(ACTION, action), field(DOMAIN, doneDomain.get())), performedBy);
            return;
        }
        final var ruleDomain = domainForReview(url, null, action == ReviewAction.BLOCK_DOMAIN ? BLOCKED_BY_DEFAULT : TRUSTED_BY_DEFAULT);
        resolveReview(urlId, action, ruleDomain, performedBy, via);
        domainsDone.add(ruleDomain);
    }

    private void closeReview(final Url url, final ReviewAction action, final String details, final String performedBy) {
        transactionOperations.executeWithoutResult(status -> reviewQueueService.resolveOpen(url, resolutionOf(action), performedBy));
        auditLogService.log(performedBy, AuditAction.RESOLVE_REVIEW, TARGET_URL, String.valueOf(url.getId()), details);
    }

    @Override
    public void deleteLink(final long urlId, final String performedBy, final String via) {
        final var url = findUrl(urlId);
        transactionOperations.executeWithoutResult(status -> urlRepository.delete(url));
        urlCache.evict(cacheKeyOf(url));
        log.info("Link deleted urlId={}", urlId);
        auditLogService.log(performedBy, AuditAction.DELETE_LINK, TARGET_URL, String.valueOf(urlId), details(via, 1, field("shortUrl", url.getShortUrl())));
    }

    private String trustFromReview(final Url url, final String domain, final String performedBy, final String via) {
        final var ruleDomain = domainForReview(url, domain, TRUSTED_BY_DEFAULT);
        final var trusted = trust(ruleDomain, null, performedBy);
        return details(via, trusted.links(), field(ACTION, ReviewAction.TRUST_DOMAIN), field(DOMAIN, ruleDomain), field("reviews", trusted.reviews()),
                warningPagesField(trusted.warningPages()));
    }

    private String blockLinkFromReview(final Url url, final Consumer<Url> tellOwner, final String performedBy, final String via) {
        final var blocked = blockAndTellOwner(url.getId(), BlockReason.ADMIN, null, ReviewResolution.LINK_BLOCKED, performedBy, candidate -> true,
                tellOwner);
        return details(via, blocked.isPresent() ? 1 : 0, field(ACTION, ReviewAction.BLOCK_LINK));
    }

    private String blockDomainFromReview(final Url url, final String domain, final String performedBy, final String via) {
        final var ruleDomain = domainForReview(url, domain, BLOCKED_BY_DEFAULT);
        final var blocked = blockLinksOn(ruleDomain, null, performedBy, null);
        return details(via, blocked.links(), field(ACTION, ReviewAction.BLOCK_DOMAIN), field(DOMAIN, ruleDomain), domainsField(blocked.domains()),
                heldField(blocked.held()));
    }

    private String blockUserFromReview(final Url url, final String performedBy, final String via) {
        final var owner = url.getOwner();
        if (owner == null) {
            throw new UrlValidationException(NO_OWNER);
        }
        final var blocked = blockLinksOf(owner, performedBy, null);
        return details(via, blocked.links(), field(ACTION, ReviewAction.BLOCK_USER), field("user", owner.getId()), domainsField(blocked.domains()));
    }

    private static String domainForReview(final Url url, final String domain, final UnaryOperator<String> byDefault) {
        final var host = DomainNames.hostOf(url.getLongUrl()).orElseThrow(() -> new UrlValidationException(NOT_A_DOMAIN));
        final var ruleDomain = ruleDomain(StringUtils.isBlank(domain) ? byDefault.apply(host) : domain);
        if (!covers(host, ruleDomain)) {
            throw new UrlValidationException(NOT_COVERED.formatted(ruleDomain, host));
        }
        return ruleDomain;
    }

    private static ReviewResolution resolutionOf(final ReviewAction action) {
        return switch (action) {
            case OK -> ReviewResolution.OK;
            case TRUST_DOMAIN -> ReviewResolution.DOMAIN_TRUSTED;
            case BLOCK_LINK -> ReviewResolution.LINK_BLOCKED;
            case BLOCK_DOMAIN -> ReviewResolution.DOMAIN_BLOCKED;
            case BLOCK_USER -> ReviewResolution.USER_BLOCKED;
        };
    }

    private Affected blockLinksOn(final String domain, final String reason, final String performedBy, final Integer maxLinks) {
        final var rulesBelow = rulesBelow(domain, domainRuleRepository.findAll());
        final var ids = destinationsOn(domain, afterId -> urlRepository.findActiveDestinationsAfter(afterId, FIRST_PAGE)).stream()
                .filter(destination -> isBlockedBy(domain, rulesBelow, destination.longUrl()))
                .map(LinkDestination::id)
                .toList();
        refuseOverTheCap(ids.size(), maxLinks);
        saveRule(domain, DomainRuleType.BLOCK, reason, performedBy);
        final var domains = customDomainModeration.disableMatching(domain, reason);
        final var blocked = new ArrayList<Url>();
        final var count = eachLink(ids, Long::longValue, id -> blockOnDomain(id, domain, rulesBelow, performedBy, blocked::add));
        notifyOwners(blocked);
        return new Affected(count, domains, convertHeldOn(domain, rulesBelow));
    }

    private int convertHeldOn(final String domain, final List<DomainRule> rulesBelow) {
        final List<Long> heldIds;
        try {
            heldIds = destinationsOn(domain, afterId -> urlRepository.findBlockedDestinationsAfter(HELD, afterId, FIRST_PAGE)).stream()
                    .filter(destination -> isBlockedBy(domain, rulesBelow, destination.longUrl()))
                    .map(LinkDestination::id)
                    .toList();
        } catch (final RuntimeException exception) {
            log.warn("Reading the held links of a blocked domain failed, continuing domain={}", domain, exception);
            return 0;
        }
        return convertHeld(heldIds, domain, rulesBelow);
    }

    private int convertHeld(final List<Long> heldIds, final String domain, final List<DomainRule> rulesBelow) {
        return eachLink(heldIds, Long::longValue, id -> convertHeldLink(id, domain, rulesBelow));
    }

    private boolean convertHeldLink(final long urlId, final String domain, final List<DomainRule> rulesBelow) {
        final var converted = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(url -> !url.isActive() && url.getBlockReason() == BlockReason.CAMPAIGN && isBlockedBy(domain, rulesBelow, url.getLongUrl()))
                .map(url -> {
                    url.setBlockReason(BlockReason.DOMAIN_RULE);
                    url.setBlockDetail(StringUtils.abbreviate(domain, MAX_COLUMN_LENGTH));
                    url.setBlockedAt(LocalDateTime.now());
                    return urlRepository.save(url);
                })
                .orElse(null)));
        converted.ifPresent(url -> {
            log.info("Held link blocked by a domain rule urlId={}", url.getId());
            evictNowAndAgainLater(cacheKeyOf(url));
        });
        return converted.isPresent();
    }

    private boolean blockOnDomain(final long urlId, final String domain, final List<DomainRule> rulesBelow, final String performedBy,
                                  final Consumer<Url> blockedLinks) {
        return block(urlId, BlockReason.DOMAIN_RULE, domain, ReviewResolution.DOMAIN_BLOCKED, performedBy,
                url -> url.isActive() && isBlockedBy(domain, rulesBelow, url.getLongUrl()))
                .map(url -> {
                    alertWhenAutomatic(url, performedBy);
                    blockedLinks.accept(url);
                    return true;
                })
                .orElse(false);
    }

    private Map<String, List<Long>> activeLinkIdsBlockedBy(final Collection<String> domains, final List<DomainRule> rules) {
        return linkIdsBlockedBy(domains, rules, afterId -> urlRepository.findActiveDestinationsAfter(afterId, FIRST_PAGE));
    }

    private Map<String, List<Long>> heldLinkIdsBlockedBy(final Collection<String> domains, final List<DomainRule> rules) {
        return linkIdsBlockedBy(domains, rules, afterId -> urlRepository.findBlockedDestinationsAfter(HELD, afterId, FIRST_PAGE));
    }

    private static Map<String, List<Long>> linkIdsBlockedBy(final Collection<String> domains, final List<DomainRule> rules,
                                                           final LongFunction<List<LinkDestination>> pageAfter) {
        final var rulesBelow = domains.stream().collect(Collectors.toMap(Function.identity(), domain -> rulesBelow(domain, rules)));
        final var ids = new HashMap<String, List<Long>>();
        forEachDestination(pageAfter, destination ->
                DomainNames.hostOf(destination.longUrl()).ifPresent(host -> rulesBelow.forEach((domain, below) -> {
                    if (hostIsBlockedBy(domain, below, host)) {
                        ids.computeIfAbsent(domain, key -> new ArrayList<>()).add(destination.id());
                    }
                })));
        return ids;
    }

    private boolean isAllowedByItsRule(final String domain) {
        return domainRuleRepository.findByDomain(domain).filter(rule -> rule.getType() == DomainRuleType.ALLOW).isPresent();
    }

    private static DomainRuleType ruleTypeOf(final String domain, final List<DomainRule> rules) {
        return rules.stream().filter(rule -> rule.getDomain().equals(domain)).map(DomainRule::getType).findFirst().orElse(null);
    }

    private static List<DomainRule> rulesBelow(final String domain, final List<DomainRule> rules) {
        return rules.stream()
                .filter(rule -> !rule.getDomain().equals(domain) && covers(rule.getDomain(), domain))
                .toList();
    }

    private static boolean isBlockedBy(final String domain, final List<DomainRule> rulesBelow, final String longUrl) {
        return DomainNames.hostOf(longUrl).filter(host -> hostIsBlockedBy(domain, rulesBelow, host)).isPresent();
    }

    private static boolean hostIsBlockedBy(final String domain, final List<DomainRule> rulesBelow, final String host) {
        return covers(host, domain) && rulesBelow.stream()
                .filter(rule -> covers(host, rule.getDomain()))
                .max(Comparator.comparingInt(rule -> rule.getDomain().length()))
                .filter(rule -> rule.getType() == DomainRuleType.ALLOW)
                .isEmpty();
    }

    private Affected blockLinksOf(final User user, final String performedBy, final Integer maxLinks) {
        if (userRepository.existsByIdAndAuthoritiesName(user.getId(), ROLE_ADMIN)) {
            throw new UrlValidationException(ADMIN_ACCOUNT);
        }
        final var ids = urlRepository.findActiveIdsByOwner(user);
        refuseOverTheCap(ids.size(), maxLinks);
        if (!Boolean.FALSE.equals(user.getActive())) {
            userService.deactivate(user);
        }
        final var domains = customDomainModeration.disableForUser(user.getId(), performedBy);
        final var blocked = eachLink(ids, Long::longValue, id -> block(id, BlockReason.USER_BLOCKED, null, ReviewResolution.USER_BLOCKED, performedBy,
                url -> url.isActive() && ownedBy(url, user)).isPresent());
        scammerSignalService.invalidate();
        return new Affected(blocked, domains, 0);
    }

    private Trusted trust(final String domain, final String reason, final String performedBy) {
        saveRule(domain, DomainRuleType.ALLOW, reason, performedBy);
        final var reviews = resolveOpenReviewsOn(domain, ReviewResolution.DOMAIN_TRUSTED, performedBy);
        final var blocked = destinationsOn(domain, afterId -> urlRepository.findBlockedDestinationsAfter(RESTORED_BY_ALLOW, afterId, FIRST_PAGE));
        final var restored = eachLink(blocked, LinkDestination::id, this::restoreUnlessStillBlocked);
        final var flagged = destinationsOn(domain, afterId -> urlRepository.findWarningPageDestinationsAfter(afterId, FIRST_PAGE));
        final var warningPages = eachLink(flagged, LinkDestination::id, destination -> clearWarningPageOf(destination.id()));
        if (warningPages > 0) {
            log.info("Allow rule cleared the warning page on links domain={} links={}", domain, warningPages);
        }
        return new Trusted(reviews, restored, warningPages);
    }

    @Override
    public UrlResponse clearWarningPage(final long urlId, final String performedBy) {
        final var url = findUrl(urlId);
        final var cleared = clearWarningPageOf(urlId);
        if (!cleared) {
            evictNowAndAgainLater(cacheKeyOf(url));
        }
        auditLogService.log(performedBy, AuditAction.CLEAR_WARNING_PAGE, TARGET_URL, String.valueOf(urlId),
                String.join(DETAIL_SEPARATOR, field("shortUrl", url.getShortUrl()), field("links", cleared ? 1 : 0)));
        return UrlResponse.from(findUrl(urlId));
    }

    private boolean clearWarningPageOf(final long urlId) {
        final var cleared = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(Url::isWarningPage)
                .map(url -> {
                    url.setWarningPage(false);
                    return urlRepository.save(url);
                })
                .orElse(null)));
        cleared.ifPresent(url -> {
            log.info("Warning page cleared urlId={}", url.getId());
            evictNowAndAgainLater(cacheKeyOf(url));
        });
        return cleared.isPresent();
    }

    private boolean restoreUnlessStillBlocked(final LinkDestination destination) {
        if (linkCheckService.check(destination.longUrl()).outcome() == LinkVerdict.Outcome.BLOCK) {
            log.info("Link stays blocked under the new allow rule urlId={}", destination.id());
            return false;
        }
        return reactivate(destination.id(), url -> RESTORED_BY_ALLOW.contains(url.getBlockReason())
                && url.getThreatType() == null
                && destination.longUrl().equals(url.getLongUrl()));
    }

    private int resolveOpenReviewsOn(final String domain, final ReviewResolution resolution, final String performedBy) {
        final var urls = new LinkedHashMap<Long, Url>();
        var pageNumber = 0;
        Page<LinkReview> page;
        do {
            page = reviewQueueService.open(PageRequest.of(pageNumber++, PAGE_SIZE), null);
            page.getContent().stream()
                    .map(LinkReview::getUrl)
                    .filter(url -> isOn(url.getLongUrl(), domain))
                    .forEach(url -> urls.putIfAbsent(url.getId(), url));
        } while (page.hasNext());
        return eachLink(List.copyOf(urls.values()), Url::getId, url -> {
            transactionOperations.executeWithoutResult(status -> reviewQueueService.resolveOpen(url, resolution, performedBy));
            return true;
        });
    }

    @Override
    public boolean holdForCampaign(final long urlId, final long campaignId, final String shape) {
        return block(urlId, BlockReason.CAMPAIGN, shape, ReviewResolution.LINK_BLOCKED, SYSTEM,
                url -> url.isActive() && url.getOwner() == null && stillHolds(campaignId, urlId))
                .isPresent();
    }

    private boolean stillHolds(final long campaignId, final long urlId) {
        if (campaignRepository.findForHold(campaignId).filter(campaign -> campaign.getStatus() != CampaignStatus.ALLOWED).isPresent()) {
            return true;
        }
        log.info("Hold skipped because the shape is allowed campaignId={} urlId={}", campaignId, urlId);
        return false;
    }

    @Override
    public int restoreCampaignHolds(final List<Long> urlIds) {
        if (urlIds.isEmpty()) {
            return 0;
        }
        return eachLink(urlRepository.findBlockedDestinationsByIdIn(HELD, urlIds), LinkDestination::id, this::restoreHoldUnlessStillBlocked);
    }

    private boolean restoreHoldUnlessStillBlocked(final LinkDestination destination) {
        final var verdict = linkCheckService.check(destination.longUrl());
        if (verdict.outcome() == LinkVerdict.Outcome.BLOCK) {
            reblockHeld(destination, verdict);
            return false;
        }
        return reactivate(destination.id(), url -> url.getBlockReason() == BlockReason.CAMPAIGN
                && url.getThreatType() == null
                && destination.longUrl().equals(url.getLongUrl()));
    }

    private void reblockHeld(final LinkDestination destination, final LinkVerdict verdict) {
        if (verdict.blockReason() == null || verdict.blockReason() == BlockReason.CAMPAIGN) {
            log.info("Held link stays blocked under the allowed shape urlId={}", destination.id());
            return;
        }
        transactionOperations.executeWithoutResult(status -> urlRepository.findById(destination.id())
                .filter(url -> url.getBlockReason() == BlockReason.CAMPAIGN && destination.longUrl().equals(url.getLongUrl()))
                .ifPresent(url -> {
                    url.setBlockReason(verdict.blockReason());
                    url.setBlockDetail(StringUtils.abbreviate(verdict.blockDetail(), MAX_COLUMN_LENGTH));
                    url.setBlockedAt(LocalDateTime.now());
                    if (verdict.blockReason() == BlockReason.SAFE_BROWSING) {
                        url.setThreatType(ThreatType.MALWARE);
                    }
                    reviewQueueService.resolveOpen(urlRepository.save(url), ReviewResolution.LINK_BLOCKED, SYSTEM);
                    log.info("Held link blocked for its own reason under the allowed shape urlId={} reason={}", url.getId(), verdict.blockReason());
                }));
    }

    private Optional<Url> blockAndTellOwner(final long urlId, final BlockReason reason, final String detail, final ReviewResolution resolution,
                                            final String performedBy, final Predicate<Url> stillApplies, final Consumer<Url> tellOwner) {
        final var blocked = block(urlId, reason, detail, resolution, performedBy, stillApplies);
        blocked.ifPresent(url -> {
            tellOwner.accept(url);
            alertWhenAutomatic(url, performedBy);
        });
        return blocked;
    }

    private void alertWhenAutomatic(final Url url, final String performedBy) {
        if (SYSTEM.equals(performedBy)) {
            eventPublisher.publishEvent(new AdminAlertEvent.LinkBlocked(AlertedLink.of(url), url.getBlockReason(), url.getBlockDetail()));
        }
    }

    private Optional<Url> block(final long urlId, final BlockReason reason, final String detail, final ReviewResolution resolution,
                                final String performedBy, final Predicate<Url> stillApplies) {
        final var blocked = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(url -> !isBlocked(url) && stillApplies.test(url))
                .map(url -> {
                    url.setActive(false);
                    url.setBlockReason(reason);
                    url.setBlockDetail(StringUtils.abbreviate(detail, MAX_COLUMN_LENGTH));
                    url.setBlockedAt(LocalDateTime.now());
                    if (reason == BlockReason.SAFE_BROWSING) {
                        url.setThreatType(ThreatType.MALWARE);
                    }
                    final var saved = urlRepository.save(url);
                    if (reason != BlockReason.CAMPAIGN) {
                        reviewQueueService.resolveOpen(saved, resolution, performedBy);
                    }
                    return saved;
                })
                .orElse(null)));
        blocked.ifPresent(url -> {
            log.info("Link blocked urlId={} reason={}", url.getId(), reason);
            evictNowAndAgainLater(cacheKeyOf(url));
        });
        return blocked;
    }

    private boolean reactivate(final long urlId, final Predicate<Url> stillApplies) {
        final var restored = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(url -> !url.isActive() && stillApplies.test(url))
                .filter(url -> domainIsRemoved(url) || aliasIsFree(url))
                .map(url -> {
                    final var turnedOn = !domainIsRemoved(url);
                    url.setActive(turnedOn);
                    url.setBlockReason(null);
                    url.setBlockDetail(null);
                    url.setBlockedAt(null);
                    return new Restored(urlRepository.save(url), turnedOn);
                })
                .orElse(null)));
        restored.ifPresent(link -> {
            if (link.turnedOn()) {
                log.info("Link reactivated urlId={}", link.url().getId());
            } else {
                log.info("Link block lifted, but the link stays off because its domain was removed urlId={} domainId={}", link.url().getId(),
                        link.url().domainId());
            }
            evictNowAndAgainLater(cacheKeyOf(link.url()));
        });
        return restored.map(Restored::turnedOn).orElse(false);
    }

    private static boolean domainIsRemoved(final Url url) {
        return url.getDomain() != null && url.getDomain().getStatus() == CustomDomainStatus.DELETED;
    }

    private boolean aliasIsFree(final Url url) {
        if (urlRepository.aliasActive(url.domainId(), url.getShortUrl())) {
            log.warn("Link stays inactive because another active link holds its alias urlId={}", url.getId());
            return false;
        }
        return true;
    }

    private <T> int eachLink(final List<T> links, final ToLongFunction<T> idOf, final Predicate<T> action) {
        var changed = 0;
        for (final var link : links) {
            try {
                if (action.test(link)) {
                    changed++;
                }
            } catch (final RuntimeException exception) {
                log.warn("Moderating a link failed, continuing with the next one urlId={}", idOf.applyAsLong(link), exception);
            }
        }
        return changed;
    }

    private static List<LinkDestination> destinationsOn(final String domain, final LongFunction<List<LinkDestination>> pageAfter) {
        final var matching = new ArrayList<LinkDestination>();
        forEachDestination(pageAfter, destination -> {
            if (isOn(destination.longUrl(), domain)) {
                matching.add(destination);
            }
        });
        return matching;
    }

    private static void forEachDestination(final LongFunction<List<LinkDestination>> pageAfter, final Consumer<LinkDestination> action) {
        var afterId = 0L;
        List<LinkDestination> page;
        do {
            page = pageAfter.apply(afterId);
            page.forEach(action);
            afterId = page.isEmpty() ? afterId : page.getLast().id();
        } while (page.size() == PAGE_SIZE);
    }

    private void saveRule(final String domain, final DomainRuleType type, final String reason, final String performedBy) {
        transactionOperations.executeWithoutResult(status -> {
            final var rule = domainRuleRepository.findByDomain(domain)
                    .orElseGet(() -> DomainRule.builder().domain(domain).createdBy(performedBy).build());
            rule.setType(type);
            if (StringUtils.isNotBlank(reason)) {
                rule.setReason(StringUtils.abbreviate(reason, MAX_COLUMN_LENGTH));
            }
            domainRuleRepository.save(rule);
        });
        log.info("Domain rule saved domain={} type={}", domain, type);
        eventPublisher.publishEvent(new BlocklistConfigurationChangedEvent());
    }

    private void notifyOwners(final List<Url> blocked) {
        blocked.stream()
                .filter(url -> url.getOwner() != null)
                .collect(Collectors.groupingBy(url -> url.getOwner().getId(), LinkedHashMap::new, Collectors.toList()))
                .values()
                .forEach(this::notifyOwnerOf);
    }

    private void notifyOwnerOf(final List<Url> links) {
        if (links.size() == 1) {
            notifyOwner(links.getFirst());
            return;
        }
        final var owner = links.getFirst().getOwner();
        try {
            sendingEmailService.sendEmailUrlsBlocked(owner, List.copyOf(links));
        } catch (final RuntimeException exception) {
            log.warn("Telling the owner about blocked links failed ownerId={} links={}", owner.getId(), links.size(), exception);
        }
    }

    private void notifyOwner(final Url url) {
        if (url.getOwner() == null) {
            return;
        }
        try {
            if (url.getBlockReason() == BlockReason.SAFE_BROWSING) {
                sendingEmailService.sendEmailUrlMalwareDetected(url.getOwner(), url,
                        StringUtils.defaultIfBlank(url.getBlockDetail(), ThreatType.MALWARE.name()));
            } else {
                sendingEmailService.sendEmailUrlBlocked(url.getOwner(), url);
            }
        } catch (final RuntimeException exception) {
            log.warn("Telling the owner about a blocked link failed urlId={}", url.getId(), exception);
        }
    }

    private void evictNowAndAgainLater(final String cacheKey) {
        urlCache.evict(cacheKey);
        try {
            taskScheduler.schedule(() -> urlCache.evict(cacheKey), Instant.now().plus(SECOND_EVICTION_DELAY));
        } catch (final RuntimeException exception) {
            log.warn("Scheduling the second URL cache eviction failed", exception);
        }
    }

    private static String cacheKeyOf(final Url url) {
        return UrlCacheKeys.of(url.domainId(), url.getShortUrl());
    }

    private Url findUrl(final long urlId) {
        return urlRepository.findById(urlId).orElseThrow(() -> new UrlNotFoundException("Url doesn't exist"));
    }

    private User findUser(final long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    private static void refuseOverTheCap(final int affected, final Integer maxLinks) {
        if (maxLinks != null && affected > maxLinks) {
            throw new UrlValidationException(OVER_THE_CAP.formatted(affected, maxLinks));
        }
    }

    private static String ruleDomain(final String domain) {
        return ruleDomainOf(domain).orElseThrow(() -> new UrlValidationException(NOT_A_DOMAIN));
    }

    private static Optional<String> ruleDomainOf(final String domain) {
        return DomainNames.hostOf(StringUtils.strip(domain))
                .filter(host -> !DomainNames.isIpLiteral(host))
                .filter(DomainNames::isUnderRegistrySuffix);
    }

    private static boolean isOn(final String longUrl, final String domain) {
        return DomainNames.hostOf(longUrl).filter(host -> covers(host, domain)).isPresent();
    }

    private static boolean covers(final String host, final String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean isBlocked(final Url url) {
        return url.getBlockReason() != null || url.getThreatType() != null;
    }

    private static boolean ownedBy(final Url url, final User user) {
        return url.getOwner() != null && Objects.equals(url.getOwner().getId(), user.getId());
    }

    private static String details(final String via, final int links, final String... fields) {
        return Stream.concat(Arrays.stream(fields), Stream.of(field("links", links), field("via", via)))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(DETAIL_SEPARATOR));
    }

    private static String field(final String name, final Object value) {
        return value == null ? null : name + ": " + value;
    }

    private static String domainsField(final int domains) {
        return domains > 0 ? field(DOMAINS, domains) : null;
    }

    private static String heldField(final int held) {
        return held > 0 ? field(HELD_LINKS, held) : null;
    }

    private static String warningPagesField(final int warningPages) {
        return warningPages > 0 ? field(WARNING_PAGES_CLEARED, warningPages) : null;
    }

    private record Trusted(int reviews, int links, int warningPages) {
    }

    private record Affected(int links, int domains, int held) {
    }

    private record Restored(Url url, boolean turnedOn) {
    }

    private record Scan(List<DomainRule> rules, Map<String, List<Long>> idsByDomain, Map<String, List<Long>> heldIdsByDomain) {
    }

}
