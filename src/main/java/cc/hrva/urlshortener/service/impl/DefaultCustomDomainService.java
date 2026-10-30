package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.domain.CustomDomainApplyDto;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.MyDomainsResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.CustomDomainService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainApplied;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.domain.HostnameRefusal;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultCustomDomainService implements CustomDomainService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String ACCOUNT_DEACTIVATED = "Account is deactivated";
    private static final String TURNED_OFF = "Custom domains aren't available right now.";
    private static final String TOO_MANY_APPLICATIONS = "You've applied for a lot of domains today. Try again tomorrow.";
    private static final String OVER_THE_LIMIT = "You can have %s. Remove one to apply for another.";
    private static final String TAKEN = "This domain has already been applied for.";
    private static final String NOT_FOUND = "Custom domain not found";
    private static final String CHANGED = "This domain changed. Reload and try again.";
    private static final String NOT_REMOVABLE_YET = "This domain can't be removed yet.";
    private static final String PUNYCODE_FLAG = "International name (punycode): check it doesn't imitate another domain";
    private static final String PUNYCODE_PREFIX = "xn--";
    private static final String APPLY_LIMIT = "domain-apply:user:";
    private static final int APPLICATIONS_PER_USER = 5;
    private static final Duration APPLY_WINDOW = Duration.ofHours(24);
    private static final Duration ENDINGS_SHOWN = Duration.ofDays(30);
    private static final int MAX_FLAGS_LENGTH = 1000;
    private static final String FLAG_SEPARATOR = "\n";
    private static final String HOSTNAME_KEY_CONSTRAINT = "ux_custom_domain_hostname_key";

    private final UserService userService;
    private final CustomDomainRepository customDomainRepository;
    private final UrlRepository urlRepository;
    private final CustomDomainHostnames customDomainHostnames;
    private final CustomDomainLimits customDomainLimits;
    private final CustomDomainResponses customDomainResponses;
    private final LinkCheckService linkCheckService;
    private final AccountLock accountLock;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    public MyDomainsResponse mine() {
        final var user = currentUser();
        final var domains = customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(user.getId());
        final var cutoff = LocalDateTime.now(clock).minus(ENDINGS_SHOWN);
        final var shown = domains.stream()
                .filter(domain -> !endedBefore(domain, cutoff))
                .map(this::response)
                .toList();
        final var used = domains.stream().filter(domain -> CustomDomainStatus.COUNTS_FOR_USER.contains(domain.getStatus())).count();
        final var limit = customDomainLimits.limitFor(user.getId());
        return new MyDomainsResponse(shown, limit, used, enabled() && used < limit);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CustomDomainResponse apply(final CustomDomainApplyDto customDomainApplyDto) {
        if (!enabled()) {
            throw new FeatureUnavailableException(TURNED_OFF);
        }
        final var user = currentUser();
        requireActive(user);
        requireWithinApplyLimit(user);
        final var hostname = customDomainHostnames.normalize(customDomainApplyDto.getHostname());
        final var reason = StringUtils.strip(customDomainApplyDto.getReason());
        final var flags = flagsOf(hostname, linkCheckService.check("https://" + hostname + "/"), user);

        final CustomDomain created;
        try {
            created = transactionOperations.execute(status -> insert(user, hostname, reason, flags));
        } catch (final DataIntegrityViolationException exception) {
            if (violates(exception, HOSTNAME_KEY_CONSTRAINT) || customDomainRepository.findByHostnameKey(hostname).isPresent()) {
                log.info("Custom domain application lost a race for its hostname userId={}", user.getId());
                throw new CustomDomainConflictException(TAKEN);
            }
            throw exception;
        }
        log.info("Custom domain applied for domainId={} userId={} flags={}", created.getId(), user.getId(), flags.size());
        return customDomainResponses.of(created, 0, clock);
    }

    @Override
    public CustomDomainResponse get(final long id) {
        return response(ownedDomain(id));
    }

    @Override
    @Transactional
    public DomainRemovalResponse remove(final long id, final DomainLinkMode mode) {
        final var domain = ownedDomain(id);
        if (domain.getStatus() != CustomDomainStatus.PENDING_REVIEW) {
            throw new CustomDomainConflictException(NOT_REMOVABLE_YET);
        }
        if (customDomainRepository.markDeleted(domain.getId(), CustomDomainStatus.PENDING_REVIEW, LocalDateTime.now(clock)) != 1) {
            log.info("Custom domain application changed before its withdrawal domainId={}", domain.getId());
            throw new CustomDomainConflictException(CHANGED);
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(domain.getId()));
        log.info("Custom domain application withdrawn domainId={}", domain.getId());
        return DomainRemovalResponse.nothing();
    }

    private CustomDomain insert(final User user, final String hostname, final String reason, final List<String> flags) {
        final var owner = accountLock.lock(user);
        requireActive(owner);
        final var limit = customDomainLimits.limitFor(owner.getId());
        if (customDomainLimits.usedBy(owner.getId()) >= limit) {
            log.info("Custom domain application refused at the limit userId={} limit={}", owner.getId(), limit);
            throw new ApiException(OVER_THE_LIMIT.formatted(limit == 1 ? "1 custom domain" : limit + " custom domains"));
        }
        if (customDomainRepository.findByHostnameKey(hostname).isPresent()) {
            log.info("Custom domain application refused for a taken hostname userId={}", owner.getId());
            throw new CustomDomainConflictException(TAKEN);
        }
        final var now = LocalDateTime.now(clock);
        final var saved = customDomainRepository.saveAndFlush(CustomDomain.builder()
                .owner(owner)
                .hostname(hostname)
                .hostnameKey(hostname)
                .status(CustomDomainStatus.PENDING_REVIEW)
                .reason(reason)
                .applyFlags(flags)
                .createdAt(now)
                .updatedAt(now)
                .build());
        eventPublisher.publishEvent(new DomainApplied(saved.getId(), hostname, owner.getId(), flags));
        eventPublisher.publishEvent(new CustomDomainChangedEvent(saved.getId()));
        return saved;
    }

    private List<String> flagsOf(final String hostname, final LinkVerdict verdict, final User user) {
        if (verdict.outcome() == LinkVerdict.Outcome.BLOCK) {
            log.info("Custom domain application refused by the link check userId={}", user.getId());
            throw HostnameRefusal.RESERVED.toException();
        }
        final var candidates = new ArrayList<String>();
        if (Arrays.stream(hostname.split("\\.")).anyMatch(label -> label.startsWith(PUNYCODE_PREFIX))) {
            candidates.add(PUNYCODE_FLAG);
        }
        if (verdict.outcome() == LinkVerdict.Outcome.FLAG) {
            candidates.addAll(verdict.reasons());
        }
        return withinColumn(candidates);
    }

    private static List<String> withinColumn(final List<String> candidates) {
        final var flags = new ArrayList<String>();
        var length = 0;
        for (final var candidate : candidates) {
            final var flag = StringUtils.normalizeSpace(candidate);
            if (StringUtils.isEmpty(flag)) {
                continue;
            }
            final var added = flags.isEmpty() ? flag.length() : FLAG_SEPARATOR.length() + flag.length();
            if (length + added <= MAX_FLAGS_LENGTH) {
                flags.add(flag);
                length += added;
            }
        }
        return List.copyOf(flags);
    }

    private void requireWithinApplyLimit(final User user) {
        if (!withinApplyLimit(user)) {
            log.info("Custom domain application refused by the rate limit userId={}", user.getId());
            throw new RateLimitExceededException(TOO_MANY_APPLICATIONS);
        }
    }

    private boolean withinApplyLimit(final User user) {
        try {
            return rateLimiter.tryAcquire(APPLY_LIMIT + user.getId(), APPLICATIONS_PER_USER, APPLY_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Custom domain application rate limit unavailable, allowing the request userId={}", user.getId());
            return true;
        }
    }

    private CustomDomain ownedDomain(final long id) {
        final var user = currentUser();
        return customDomainRepository.findByIdAndOwnerId(id, user.getId())
                .filter(domain -> domain.getStatus() != CustomDomainStatus.DELETED)
                .orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
    }

    private CustomDomainResponse response(final CustomDomain domain) {
        return customDomainResponses.of(domain, urlRepository.countByDomain_Id(domain.getId()), clock);
    }

    private static boolean endedBefore(final CustomDomain domain, final LocalDateTime cutoff) {
        final var endedAt = switch (domain.getStatus()) {
            case DELETED -> ObjectUtils.firstNonNull(domain.getDeletedAt(), domain.getUpdatedAt());
            case REJECTED -> ObjectUtils.firstNonNull(domain.getReviewedAt(), domain.getUpdatedAt());
            default -> null;
        };
        return endedAt != null && endedAt.isBefore(cutoff);
    }

    private boolean enabled() {
        return appProperties.getDomains().isEnabled();
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }
        return user;
    }

    private static void requireActive(final User user) {
        if (Boolean.FALSE.equals(user.getActive())) {
            log.info("Custom domain application refused for a deactivated account userId={}", user.getId());
            throw new DisabledException(ACCOUNT_DEACTIVATED);
        }
    }

    private static boolean violates(final DataIntegrityViolationException exception, final String constraint) {
        final var violation = ExceptionUtils.throwableOfType(exception, ConstraintViolationException.class);
        return violation != null && StringUtils.containsIgnoreCase(violation.getConstraintName(), constraint);
    }

}
