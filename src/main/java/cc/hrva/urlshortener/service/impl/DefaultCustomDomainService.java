package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.domain.CustomDomainApplyDto;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainRequestResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.MyDomainsResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.CustomDomainCapacityException;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.NotSignedInException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import cc.hrva.urlshortener.model.enums.UseAgain;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.CustomDomainService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainApplied;
import cc.hrva.urlshortener.service.domain.CustomDomainApproval;
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
import java.util.OptionalInt;
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
import org.springframework.transaction.support.TransactionCallback;
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
    private static final String OVER_THE_LIMIT = "You can have %s. Remove or withdraw one to apply for another.";
    private static final String TAKEN = "This domain has already been applied for.";
    private static final String NOT_FOUND = "Custom domain not found";
    private static final String NOTHING_TO_RETRY = "There's nothing to retry for this domain.";
    private static final String CANT_SET_UP_NOW = "We can't set up your domain right now. Try again later.";
    private static final String PUNYCODE_FLAG = "International name (punycode): check it doesn't imitate another domain";
    private static final String PUNYCODE_PREFIX = "xn--";
    private static final String APPLY_LIMIT = "domain-apply:user:";
    private static final String CHECK_LIMIT = "domain-check:domain:";
    private static final String RETRY_LIMIT = "domain-retry:domain:";
    private static final String RETRY_TOO_SOON = "Wait a few seconds and try again.";
    private static final String CHANGES_UNAVAILABLE = "Domain applications and retries are paused for a moment. Try again in a minute.";
    private static final Duration LIMITER_RETRY = Duration.ofMinutes(1);
    private static final Duration CHECK_WINDOW = Duration.ofSeconds(15);
    private static final int APPLICATIONS_PER_USER = 5;
    private static final Duration APPLY_WINDOW = Duration.ofHours(24);
    private static final Duration ENDINGS_SHOWN = Duration.ofDays(30);
    private static final int MAX_FLAGS_LENGTH = 1000;
    private static final String FLAG_SEPARATOR = "\n";
    private static final String HOSTNAME_KEY_CONSTRAINT = "ux_custom_domain_hostname_key";
    private static final String STILL_BEING_REMOVED = "This domain is still being removed from Cloudflare. Try again in a few minutes.";
    private static final String USED_AGAIN = "use again";
    private static final String HTTPS = "https://";

    private final UserService userService;
    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainRequestRepository customDomainRequestRepository;
    private final UrlRepository urlRepository;
    private final CustomDomainCapLock customDomainCapLock;
    private final CustomDomainHostnames customDomainHostnames;
    private final CustomDomainLimits customDomainLimits;
    private final CustomDomainResponses customDomainResponses;
    private final CustomDomainLifecycle customDomainLifecycle;
    private final CustomDomainProvisioner customDomainProvisioner;
    private final CustomDomainSetup customDomainSetup;
    private final CustomDomainRemoval customDomainRemoval;
    private final CustomDomainApproval customDomainApproval;
    private final LinkCheckService linkCheckService;
    private final ScammerSignalService scammerSignalService;
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
        final var listed = domains.stream()
                .filter(domain -> !endedBefore(domain, cutoff))
                .toList();
        final var possibleScammer = listed.stream().anyMatch(domain -> domain.getStatus() == CustomDomainStatus.DELETED) && possibleScammer(user);
        final var shown = listed.stream()
                .map(domain -> response(domain, useAgainOf(domain, possibleScammer)))
                .toList();
        final var used = domains.stream().filter(domain -> CustomDomainStatus.COUNTS_FOR_USER.contains(domain.getStatus())).count();
        final var limit = customDomainLimits.limitFor(user.getId());
        final var request = customDomainRequestRepository.findFirstByUser_IdOrderByCreatedAtDescIdDesc(user.getId())
                .filter(newest -> newest.getStatus() == DomainRequestStatus.WAITING || !newest.getReviewedAt().isBefore(cutoff))
                .orElse(null);
        final var waiting = request != null && request.getStatus() == DomainRequestStatus.WAITING;
        final var canRequest = enabled() && used >= limit && limit < DomainAllowanceDto.MAX_DOMAINS && !waiting;
        return new MyDomainsResponse(shown, limit, used, enabled() && used < limit, request == null ? null : DomainRequestResponse.of(request), canRequest);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CustomDomainResponse apply(final CustomDomainApplyDto customDomainApplyDto) {
        if (!enabled()) {
            throw new FeatureUnavailableException(TURNED_OFF);
        }
        final var user = currentUser();
        requireActive(user);
        final var hostname = customDomainHostnames.normalize(customDomainApplyDto.getHostname());
        requireBelowTheLimit(user.getId());
        requireWithinApplyLimit(user);
        final var reason = StringUtils.strip(customDomainApplyDto.getReason());
        final var flags = flagsOf(hostname, linkCheckService.check(HTTPS + hostname + "/"), user);
        final var created = inserted(user, hostname, status -> insert(user, hostname, reason, flags));
        log.info("Custom domain applied for domainId={} userId={} flags={}", created.getId(), user.getId(), flags.size());
        return customDomainResponses.of(created, 0, clock);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CustomDomainResponse useAgain(final long id) {
        if (!enabled()) {
            throw new FeatureUnavailableException(TURNED_OFF);
        }
        final var user = currentUser();
        final var removed = Objects.requireNonNull(transactionOperations.execute(status -> removedDomain(id, user)));
        if (removed.isCloudflareDeletePending()) {
            log.info("Custom domain use again refused, it is still being removed from Cloudflare domainId={}", id);
            throw new CustomDomainConflictException(STILL_BEING_REMOVED);
        }
        final var hostname = customDomainHostnames.normalize(removed.getHostname());
        requireActive(user);
        requireBelowTheLimit(user.getId());
        requireWithinApplyLimit(user);
        final var flags = flagsOf(hostname, linkCheckService.check(HTTPS + hostname + "/"), user);
        final var cloudflareTotal = skipsReview(removed, flags, user) ? cloudflareTotal(id) : OptionalInt.empty();
        final var created = inserted(user, hostname, status -> insertAgain(user, removed, flags, cloudflareTotal));
        if (created.getStatus() == CustomDomainStatus.APPROVED_PROVISIONING) {
            customDomainApproval.handOver(created.getId());
            log.info("Custom domain used again and set up straight away domainId={} removedDomainId={} userId={}", created.getId(), id, user.getId());
        } else {
            log.info("Custom domain used again, waiting for review domainId={} removedDomainId={} userId={} flags={}", created.getId(), id,
                    user.getId(), flags.size());
        }
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
        requireWithinRetryLimit(domain.getId());
        try {
            customDomainSetup.retry(domain);
        } catch (final CustomDomainCapacityException exception) {
            log.info("Custom domain retry by its owner refused by the cap or Cloudflare domainId={}", id);
            throw new CustomDomainConflictException(CANT_SET_UP_NOW);
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

    private CustomDomain inserted(final User user, final String hostname, final TransactionCallback<CustomDomain> insert) {
        try {
            return transactionOperations.execute(insert);
        } catch (final DataIntegrityViolationException exception) {
            if (violates(exception, HOSTNAME_KEY_CONSTRAINT) || customDomainRepository.findByHostnameKey(hostname).isPresent()) {
                log.info("Custom domain application lost a race for its hostname userId={}", user.getId());
                throw new CustomDomainConflictException(TAKEN);
            }
            throw exception;
        }
    }

    private CustomDomain insert(final User user, final String hostname, final String reason, final List<String> flags) {
        return pending(lockedOwner(user, hostname), hostname, reason, flags);
    }

    private CustomDomain insertAgain(final User user, final CustomDomain removed, final List<String> flags, final OptionalInt cloudflareTotal) {
        final var owner = lockedOwner(user, removed.getHostname());
        if (cloudflareTotal.isPresent() && roomToSetUp(removed.getId(), cloudflareTotal.getAsInt())) {
            return setUpStraightAway(owner, removed, flags);
        }
        return pending(owner, removed.getHostname(), removed.getReason(), flags);
    }

    private User lockedOwner(final User user, final String hostname) {
        final var owner = accountLock.lock(user);
        requireActive(owner);
        requireBelowTheLimit(owner.getId());
        requireFree(hostname, owner.getId());
        return owner;
    }

    private CustomDomain setUpStraightAway(final User owner, final CustomDomain removed, final List<String> flags) {
        final var now = LocalDateTime.now(clock);
        final var saved = customDomainRepository.saveAndFlush(CustomDomain.builder()
                .owner(owner)
                .hostname(removed.getHostname())
                .hostnameKey(removed.getHostname())
                .status(CustomDomainStatus.APPROVED_PROVISIONING)
                .reason(removed.getReason())
                .applyFlags(flags)
                .reviewedBy(USED_AGAIN)
                .reviewedAt(now)
                .nextCheckAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        eventPublisher.publishEvent(new CustomDomainChangedEvent(saved.getId()));
        return saved;
    }

    private CustomDomain pending(final User owner, final String hostname, final String reason, final List<String> flags) {
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

    private boolean skipsReview(final CustomDomain removed, final List<String> flags, final User user) {
        return flags.isEmpty() && reviewFree(removed) && !possibleScammer(user);
    }

    private boolean reviewFree(final CustomDomain removed) {
        return !removed.isRemovedByAdmin() && removed.getActivatedAt() != null && !customDomainApproval.blockedByRule(removed.getHostname());
    }

    private boolean possibleScammer(final User user) {
        return !scammerSignalService.possibleScammers(List.of(user)).isEmpty();
    }

    private OptionalInt cloudflareTotal(final long removedId) {
        try {
            return OptionalInt.of(customDomainSetup.liveTotal(removedId));
        } catch (final CustomDomainCapacityException exception) {
            log.info("Custom domain used again goes to review, Cloudflare can't take it now removedDomainId={}", removedId);
            return OptionalInt.empty();
        }
    }

    private boolean roomToSetUp(final long removedId, final int cloudflareTotal) {
        customDomainCapLock.lock();
        try {
            customDomainSetup.requireRoom(removedId, cloudflareTotal, false);
            return true;
        } catch (final CustomDomainCapacityException exception) {
            log.info("Custom domain used again goes to review, there is no room to set it up removedDomainId={}", removedId);
            return false;
        }
    }

    private UseAgain useAgainOf(final CustomDomain domain, final boolean possibleScammer) {
        if (domain.getStatus() != CustomDomainStatus.DELETED) {
            return null;
        }
        if (domain.isCloudflareDeletePending()) {
            return UseAgain.CLOUDFLARE_REMOVAL_PENDING;
        }
        if (!usable(domain.getHostname()) || heldByAnother(domain)) {
            return null;
        }
        return !possibleScammer && reviewFree(domain) ? UseAgain.SKIPS_REVIEW : UseAgain.NEEDS_REVIEW;
    }

    private boolean usable(final String hostname) {
        try {
            customDomainHostnames.normalize(hostname);
            return true;
        } catch (final FieldValidationException exception) {
            return false;
        }
    }

    private boolean heldByAnother(final CustomDomain domain) {
        return customDomainRepository.findByHostnameKey(domain.getHostname())
                .filter(holder -> !Objects.equals(holder.getId(), domain.getId()))
                .isPresent();
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

    private void requireBelowTheLimit(final long userId) {
        final var limit = customDomainLimits.limitFor(userId);
        if (customDomainLimits.usedBy(userId) >= limit) {
            log.info("Custom domain application refused at the limit userId={} limit={}", userId, limit);
            throw new ApiException(OVER_THE_LIMIT.formatted(limit == 1 ? "1 custom domain" : limit + " custom domains"));
        }
    }

    private void requireFree(final String hostname, final long userId) {
        if (customDomainRepository.findByHostnameKey(hostname).isPresent()) {
            log.info("Custom domain application refused for a taken hostname userId={}", userId);
            throw new CustomDomainConflictException(TAKEN);
        }
    }

    private void requireWithinApplyLimit(final User user) {
        final var limitKey = APPLY_LIMIT + user.getId();
        if (!withinApplyLimit(user, limitKey)) {
            log.info("Custom domain application refused by the rate limit userId={}", user.getId());
            throw new RateLimitExceededException(TOO_MANY_APPLICATIONS, rateLimiter.resetsIn(limitKey).orElse(APPLY_WINDOW));
        }
    }

    private boolean withinApplyLimit(final User user, final String limitKey) {
        try {
            return rateLimiter.tryAcquire(limitKey, APPLICATIONS_PER_USER, APPLY_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Custom domain application rate limit unavailable, refusing the request userId={}", user.getId());
            throw new ServiceBusyException(CHANGES_UNAVAILABLE, LIMITER_RETRY);
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

    private void requireWithinRetryLimit(final long id) {
        final var limitKey = RETRY_LIMIT + id;
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(limitKey, 1, CHECK_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Custom domain retry rate limit unavailable, refusing the retry domainId={}", id);
            throw new ServiceBusyException(CHANGES_UNAVAILABLE, LIMITER_RETRY);
        }
        if (!allowed) {
            log.info("Custom domain retry refused, the last one was moments ago domainId={}", id);
            throw new RateLimitExceededException(RETRY_TOO_SOON, rateLimiter.resetsIn(limitKey).orElse(CHECK_WINDOW));
        }
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

    private CustomDomain removedDomain(final long id, final User user) {
        return customDomainRepository.findByIdAndOwnerId(id, user.getId())
                .filter(domain -> domain.getStatus() == CustomDomainStatus.DELETED)
                .orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
    }

    private CustomDomainResponse response(final CustomDomain domain) {
        return response(domain, null);
    }

    private CustomDomainResponse response(final CustomDomain domain, final UseAgain useAgain) {
        return customDomainResponses.of(domain, urlRepository.countByDomain_Id(domain.getId()), useAgain, clock);
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
            throw new NotSignedInException(NOT_AUTHENTICATED);
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
