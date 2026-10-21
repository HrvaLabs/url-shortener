package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.DomainRuleNotFoundException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.ReviewAction;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.model.enums.ThreatType;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.IPAddressService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private static final String URLS_CACHE = "urls";
    private static final String DETAIL_SEPARATOR = ", ";
    private static final String TARGET_URL = "Url";
    private static final String TARGET_DOMAIN = "Domain";
    private static final String TARGET_USER = "User";
    private static final String ACTION = "action";
    private static final String DOMAIN = "domain";
    private static final String REASON = "reason";
    private static final String NOT_A_DOMAIN = "Enter a domain such as example.com, not a top-level domain or an IP address.";
    private static final String OVER_THE_CAP = "This would affect %d links, more than the %d allowed here. Use the dashboard.";
    private static final String NOT_COVERED = "%s doesn't cover this link's destination %s.";
    private static final String NO_OWNER = "This link has no owner to block.";
    private static final Set<BlockReason> RESTORED_BY_ALLOW = EnumSet.of(BlockReason.BLOCKLIST, BlockReason.SCAM_SIGNALS, BlockReason.DOMAIN_RULE);

    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final DomainRuleRepository domainRuleRepository;
    private final LinkCheckService linkCheckService;
    private final ReviewQueueService reviewQueueService;
    private final UserService userService;
    private final SendingEmailService sendingEmailService;
    private final AuditLogService auditLogService;
    private final IPAddressService ipAddressService;
    private final CacheManager cacheManager;
    private final TransactionOperations transactionOperations;

    @Override
    public UrlResponse blockLink(final long urlId, final BlockReason reason, final String detail, final String performedBy, final String via) {
        final var url = findUrl(urlId);
        final var blocked = blockAndTellOwner(urlId, reason, detail, ReviewResolution.LINK_BLOCKED, performedBy, candidate -> true);
        auditLogService.log(performedBy, "BLOCK_LINK", TARGET_URL, String.valueOf(urlId),
                details(via, blocked.isPresent() ? 1 : 0, field(REASON, reason), field("detail", detail)));
        return UrlResponse.from(blocked.orElse(url));
    }

    @Override
    public int blockDomain(final String domain, final String reason, final String performedBy, final String via, final Integer maxLinks) {
        final var ruleDomain = ruleDomain(domain);
        final var blocked = blockLinksOn(ruleDomain, reason, performedBy, maxLinks);
        auditLogService.log(performedBy, "BLOCK_DOMAIN", TARGET_DOMAIN, ruleDomain, details(via, blocked, field(REASON, reason)));
        return blocked;
    }

    @Override
    public void allowDomain(final String domain, final String reason, final String performedBy, final String via) {
        final var ruleDomain = ruleDomain(domain);
        final var trusted = trust(ruleDomain, reason, performedBy);
        auditLogService.log(performedBy, "ALLOW_DOMAIN", TARGET_DOMAIN, ruleDomain,
                details(via, trusted.links(), field(REASON, reason), field("reviews", trusted.reviews())));
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
        auditLogService.log(performedBy, "REMOVE_DOMAIN_RULE", TARGET_DOMAIN, ruleDomain, details(via, 0, field("type", removedType)));
    }

    @Override
    public int blockUser(final long userId, final String performedBy, final String via, final Integer maxLinks) {
        final var user = findUser(userId);
        final var blocked = blockLinksOf(user, performedBy, maxLinks);
        auditLogService.log(performedBy, "BLOCK_USER", TARGET_USER, String.valueOf(userId), details(via, blocked));
        return blocked;
    }

    @Override
    public int reactivateUser(final long userId, final String performedBy, final String via) {
        final var user = findUser(userId);
        if (Boolean.FALSE.equals(user.getActive())) {
            user.setActive(true);
            userService.persistUser(user);
            log.info("User reactivated id={}", userId);
        }
        final var restored = eachLink(urlRepository.findIdsByOwnerAndBlockReason(user, BlockReason.USER_BLOCKED), Long::longValue,
                id -> reactivate(id, url -> url.getBlockReason() == BlockReason.USER_BLOCKED && url.getThreatType() == null));
        auditLogService.log(performedBy, "REACTIVATE_USER", TARGET_USER, String.valueOf(userId), details(via, restored));
        return restored;
    }

    @Override
    public void resolveReview(final long urlId, final ReviewAction action, final String domain, final String performedBy, final String via) {
        final var url = findUrl(urlId);
        final var details = switch (action) {
            case OK -> details(via, 0, field(ACTION, action));
            case TRUST_DOMAIN -> trustFromReview(url, domain, performedBy, via);
            case BLOCK_LINK -> blockLinkFromReview(url, performedBy, via);
            case BLOCK_DOMAIN -> blockDomainFromReview(url, domain, performedBy, via);
            case BLOCK_USER -> blockUserFromReview(url, performedBy, via);
        };
        transactionOperations.executeWithoutResult(status -> reviewQueueService.resolveOpen(url, resolutionOf(action), performedBy));
        auditLogService.log(performedBy, "RESOLVE_REVIEW", TARGET_URL, String.valueOf(urlId), details);
    }

    @Override
    public void deleteLink(final long urlId, final String performedBy, final String via) {
        final var url = findUrl(urlId);
        transactionOperations.executeWithoutResult(status -> {
            ipAddressService.deleteRecordsForUrl(url);
            urlRepository.delete(url);
        });
        evictFromCache(url.getShortUrl());
        log.info("Link deleted urlId={}", urlId);
        auditLogService.log(performedBy, "DELETE_LINK", TARGET_URL, String.valueOf(urlId), details(via, 1, field("shortUrl", url.getShortUrl())));
    }

    private String trustFromReview(final Url url, final String domain, final String performedBy, final String via) {
        final var ruleDomain = domainForReview(url, domain, UnaryOperator.identity());
        final var trusted = trust(ruleDomain, null, performedBy);
        return details(via, trusted.links(), field(ACTION, ReviewAction.TRUST_DOMAIN), field(DOMAIN, ruleDomain), field("reviews", trusted.reviews()));
    }

    private String blockLinkFromReview(final Url url, final String performedBy, final String via) {
        final var blocked = blockAndTellOwner(url.getId(), BlockReason.ADMIN, null, ReviewResolution.LINK_BLOCKED, performedBy, candidate -> true);
        return details(via, blocked.isPresent() ? 1 : 0, field(ACTION, ReviewAction.BLOCK_LINK));
    }

    private String blockDomainFromReview(final Url url, final String domain, final String performedBy, final String via) {
        final var ruleDomain = domainForReview(url, domain, DomainNames::registrableDomain);
        final var blocked = blockLinksOn(ruleDomain, null, performedBy, null);
        return details(via, blocked, field(ACTION, ReviewAction.BLOCK_DOMAIN), field(DOMAIN, ruleDomain));
    }

    private String blockUserFromReview(final Url url, final String performedBy, final String via) {
        final var owner = url.getOwner();
        if (owner == null) {
            throw new UrlValidationException(NO_OWNER);
        }
        final var blocked = blockLinksOf(owner, performedBy, null);
        return details(via, blocked, field(ACTION, ReviewAction.BLOCK_USER), field("user", owner.getId()));
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

    private int blockLinksOn(final String domain, final String reason, final String performedBy, final Integer maxLinks) {
        final var rulesBelow = rulesBelow(domain);
        final var ids = destinationsOn(domain, afterId -> urlRepository.findActiveDestinationsAfter(afterId, FIRST_PAGE)).stream()
                .filter(destination -> !isAllowedBy(rulesBelow, destination.longUrl()))
                .map(LinkDestination::id)
                .toList();
        refuseOverTheCap(ids.size(), maxLinks);
        saveRule(domain, DomainRuleType.BLOCK, reason, performedBy);
        return eachLink(ids, Long::longValue, id -> blockAndTellOwner(id, BlockReason.DOMAIN_RULE, domain, ReviewResolution.DOMAIN_BLOCKED,
                performedBy, url -> url.isActive() && isOn(url.getLongUrl(), domain) && !isAllowedBy(rulesBelow, url.getLongUrl())).isPresent());
    }

    private List<DomainRule> rulesBelow(final String domain) {
        return domainRuleRepository.findAll().stream()
                .filter(rule -> !rule.getDomain().equals(domain) && covers(rule.getDomain(), domain))
                .toList();
    }

    private static boolean isAllowedBy(final List<DomainRule> rules, final String longUrl) {
        return DomainNames.hostOf(longUrl)
                .flatMap(host -> rules.stream()
                        .filter(rule -> covers(host, rule.getDomain()))
                        .max(Comparator.comparingInt(rule -> rule.getDomain().length())))
                .filter(rule -> rule.getType() == DomainRuleType.ALLOW)
                .isPresent();
    }

    private int blockLinksOf(final User user, final String performedBy, final Integer maxLinks) {
        final var ids = urlRepository.findActiveIdsByOwner(user);
        refuseOverTheCap(ids.size(), maxLinks);
        if (!Boolean.FALSE.equals(user.getActive())) {
            userService.deactivate(user);
        }
        return eachLink(ids, Long::longValue, id -> block(id, BlockReason.USER_BLOCKED, null, ReviewResolution.USER_BLOCKED, performedBy,
                url -> url.isActive() && ownedBy(url, user)).isPresent());
    }

    private Trusted trust(final String domain, final String reason, final String performedBy) {
        saveRule(domain, DomainRuleType.ALLOW, reason, performedBy);
        final var reviews = resolveOpenReviewsOn(domain, ReviewResolution.DOMAIN_TRUSTED, performedBy);
        final var blocked = destinationsOn(domain, afterId -> urlRepository.findBlockedDestinationsAfter(RESTORED_BY_ALLOW, afterId, FIRST_PAGE));
        final var restored = eachLink(blocked, LinkDestination::id, this::restoreUnlessStillBlocked);
        return new Trusted(reviews, restored);
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

    private Optional<Url> blockAndTellOwner(final long urlId, final BlockReason reason, final String detail, final ReviewResolution resolution,
                                            final String performedBy, final Predicate<Url> stillApplies) {
        final var blocked = block(urlId, reason, detail, resolution, performedBy, stillApplies);
        blocked.ifPresent(this::notifyOwner);
        return blocked;
    }

    private Optional<Url> block(final long urlId, final BlockReason reason, final String detail, final ReviewResolution resolution,
                                final String performedBy, final Predicate<Url> stillApplies) {
        final var blocked = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(url -> !isBlocked(url) && stillApplies.test(url))
                .map(url -> {
                    url.setActive(false);
                    url.setBlockReason(reason);
                    url.setBlockDetail(detail);
                    url.setBlockedAt(LocalDateTime.now());
                    final var saved = urlRepository.save(url);
                    reviewQueueService.resolveOpen(saved, resolution, performedBy);
                    return saved;
                })
                .orElse(null)));
        blocked.ifPresent(url -> {
            log.info("Link blocked urlId={} reason={}", url.getId(), reason);
            evictFromCache(url.getShortUrl());
        });
        return blocked;
    }

    private boolean reactivate(final long urlId, final Predicate<Url> stillApplies) {
        final var restored = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(url -> !url.isActive() && stillApplies.test(url))
                .map(url -> {
                    url.setActive(true);
                    url.setBlockReason(null);
                    url.setBlockDetail(null);
                    url.setBlockedAt(null);
                    return urlRepository.save(url);
                })
                .orElse(null)));
        restored.ifPresent(url -> {
            log.info("Link reactivated urlId={}", url.getId());
            evictFromCache(url.getShortUrl());
        });
        return restored.isPresent();
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
        var afterId = 0L;
        List<LinkDestination> page;
        do {
            page = pageAfter.apply(afterId);
            page.stream().filter(destination -> isOn(destination.longUrl(), domain)).forEach(matching::add);
            afterId = page.isEmpty() ? afterId : page.getLast().id();
        } while (page.size() == PAGE_SIZE);
        return matching;
    }

    private void saveRule(final String domain, final DomainRuleType type, final String reason, final String performedBy) {
        transactionOperations.executeWithoutResult(status -> {
            final var rule = domainRuleRepository.findByDomain(domain).orElseGet(() -> DomainRule.builder().domain(domain).build());
            rule.setType(type);
            rule.setReason(reason);
            rule.setCreatedBy(performedBy);
            rule.setCreatedAt(LocalDateTime.now());
            domainRuleRepository.save(rule);
        });
        log.info("Domain rule saved domain={} type={}", domain, type);
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

    private void evictFromCache(final String shortUrl) {
        try {
            final var cache = cacheManager.getCache(URLS_CACHE);
            if (cache != null) {
                cache.evict(shortUrl);
            }
        } catch (final RuntimeException exception) {
            log.warn("URL cache eviction failed", exception);
        }
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
        return DomainNames.hostOf(StringUtils.strip(domain))
                .filter(host -> !DomainNames.isIpLiteral(host))
                .filter(DomainNames::isUnderRegistrySuffix)
                .orElseThrow(() -> new UrlValidationException(NOT_A_DOMAIN));
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

    private record Trusted(int reviews, int links) {
    }

}
