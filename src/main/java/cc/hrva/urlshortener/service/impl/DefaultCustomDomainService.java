package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.domain.CustomDomainApplyDto;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.MyDomainsResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.CustomDomainCapacityException;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.CustomDomainService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainApplied;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval.Actor;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.domain.CustomDomainSetup;
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
import java.util.Objects;
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
    private static final String NOTHING_TO_RETRY = "There's nothing to retry for this domain.";
    private static final String CANT_SET_UP_NOW = "We can't set up your domain right now. Try again later.";
    private static final String PUNYCODE_FLAG = "International name (punycode): check it doesn't imitate another domain";
    private static final String PUNYCODE_PREFIX = "xn--";
    private static final String APPLY_LIMIT = "domain-apply:user:";
    private static final String CHECK_LIMIT = "domain-check:domain:";
    private static final Duration CHECK_WINDOW = Duration.ofSeconds(15);
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
    private final CustomDomainLifecycle customDomainLifecycle;
    private final CustomDomainProvisioner customDomainProvisioner;
    private final CustomDomainSetup customDomainSetup;
    private final CustomDomainRemoval customDomainRemoval;
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CustomDomainResponse get(final long id) {
        final var domain = Objects.requireNonNull(transactionOperations.execute(status -> ownedDomain(id)));
        if (!liveCheckable(domain) || !withinCheckLimit(domain.getId())) {
            return Objects.requireNonNull(transactionOperations.execute(status -> response(domain)));
        }
        try {
            if (domain.getStatus() == CustomDomainStatus.APPROVED_PROVISIONING) {
                customDomainProvisioner.provision(domain.getId());
            } else {
                customDomainLifecycle.checkNow(domain.getId());
            }
        } catch (final RuntimeException exception) {
            log.warn("Custom domain live check failed, serving the stored status domainId={} exception={}", domain.getId(),
                    exception.getClass().getName());
        }
        return Objects.requireNonNull(transactionOperations.execute(status -> response(ownedDomain(id))));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CustomDomainResponse retry(final long id) {
        final var domain = Objects.requireNonNull(transactionOperations.execute(status -> ownedDomain(id)));
        if (!retryable(domain)) {
            log.info("Custom domain retry refused, there is nothing to retry domainId={} status={}", id, domain.getStatus());
            throw new CustomDomainConflictException(NOTHING_TO_RETRY);
        }
        if (withinCheckLimit(domain.getId())) {
            try {
                customDomainSetup.retry(domain);
            } catch (final CustomDomainCapacityException exception) {
                log.info("Custom domain retry by its owner refused by the cap or Cloudflare domainId={}", id);
                throw new CustomDomainConflictException(CANT_SET_UP_NOW);
            }
        }
        return Objects.requireNonNull(transactionOperations.execute(status -> response(ownedDomain(id))));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DomainRemovalResponse remove(final long id, final DomainLinkMode mode) {
        final var user = currentUser();
        Objects.requireNonNull(transactionOperations.execute(status -> ownedDomain(id)));
        return customDomainRemoval.remove(id, mode, new Actor(user.getEmail(), false));
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

    private static boolean retryable(final CustomDomain domain) {
        return domain.getStatus() == CustomDomainStatus.ERROR
                || domain.getStatus() == CustomDomainStatus.APPROVED_PROVISIONING && domain.getProblem() == DomainProblem.PROVISIONING_FAILED;
    }

    private boolean liveCheckable(final CustomDomain domain) {
        return switch (domain.getStatus()) {
            case APPROVED_PROVISIONING -> domain.getNextCheckAt() == null || !domain.getNextCheckAt().isAfter(LocalDateTime.now(clock));
            case AWAITING_DNS -> true;
            case ERROR -> domain.getProblem() != null && (domain.getProblem().recheckable() || domain.getProblem() == DomainProblem.DNS_TIMED_OUT);
            case PENDING_REVIEW, ACTIVE, REJECTED, DISABLED, DELETED -> false;
        };
    }

    private boolean withinCheckLimit(final long id) {
        try {
            return rateLimiter.tryAcquire(CHECK_LIMIT + id, 1, CHECK_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Custom domain check rate limit unavailable, serving the stored status domainId={}", id);
            return false;
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
