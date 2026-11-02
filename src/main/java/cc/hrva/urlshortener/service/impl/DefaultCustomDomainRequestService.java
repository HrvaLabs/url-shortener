package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainRequestDto;
import cc.hrva.urlshortener.dto.domain.DomainRequestResponse;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.NotSignedInException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.model.CustomDomainRequest;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.service.CustomDomainRequestService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainLimitRequested;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultCustomDomainRequestService implements CustomDomainRequestService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String ACCOUNT_DEACTIVATED = "Account is deactivated";
    private static final String TURNED_OFF = "Custom domains aren't available right now.";
    private static final String REQUESTED_TOTAL = "requestedTotal";
    private static final String ABOVE_THE_LIMIT = "Choose more than your limit of %d.";
    private static final String AT_THE_MOST = "Your account already has the highest limit, %d custom domains.".formatted(DomainAllowanceDto.MAX_DOMAINS);
    private static final String BELOW_THE_LIMIT = "You can still add a domain. Ask for more once you're at your limit.";
    private static final String ALREADY_WAITING = "You already have a request waiting for review.";
    private static final String ALREADY_REVIEWED = "This request was already reviewed.";
    private static final String NOT_FOUND = "Request not found";
    private static final String TOO_MANY_REQUESTS = "You've sent several requests today. Try again tomorrow.";
    private static final String REQUEST_LIMIT = "domain-request:user:";
    private static final int REQUESTS_PER_USER = 3;
    private static final Duration REQUEST_WINDOW = Duration.ofHours(24);
    private static final String REQUESTS_PAUSED = "Requests for more domains are paused for a moment. Try again in a minute.";
    private static final Duration LIMITER_RETRY = Duration.ofMinutes(1);

    private final UserService userService;
    private final CustomDomainRequestRepository customDomainRequestRepository;
    private final CustomDomainLimits customDomainLimits;
    private final AccountLock accountLock;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DomainRequestResponse request(final DomainRequestDto domainRequestDto) {
        if (!appProperties.getDomains().isEnabled()) {
            throw new FeatureUnavailableException(TURNED_OFF);
        }
        final var user = currentUser();
        requireActive(user);
        final int requestedTotal = domainRequestDto.getRequestedTotal();
        requireRequestable(user.getId(), requestedTotal);
        requireWithinRequestLimit(user);
        final var created = Objects.requireNonNull(transactionOperations.execute(status -> insert(user, requestedTotal, domainRequestDto.getReason())));
        log.info("Custom domain limit requested requestId={} userId={} requestedTotal={}", created.getId(), user.getId(), requestedTotal);
        return DomainRequestResponse.of(created);
    }

    @Override
    @Transactional
    public void withdraw(final long id) {
        final var user = currentUser();
        final var request = customDomainRequestRepository.findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        if (request.getStatus() != DomainRequestStatus.WAITING || customDomainRequestRepository.deleteWaiting(id, user.getId()) != 1) {
            log.info("Custom domain limit request withdrawal refused, it was already reviewed requestId={}", id);
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
        log.info("Custom domain limit request withdrawn requestId={} userId={}", id, user.getId());
    }

    private CustomDomainRequest insert(final User user, final int requestedTotal, final String reason) {
        final var owner = accountLock.lock(user);
        requireActive(owner);
        final var limit = requireRequestable(owner.getId(), requestedTotal);
        final var saved = customDomainRequestRepository.saveAndFlush(CustomDomainRequest.builder()
                .user(owner)
                .requestedTotal(requestedTotal)
                .limitAtRequest(limit)
                .reason(reason)
                .status(DomainRequestStatus.WAITING)
                .createdAt(LocalDateTime.now(clock))
                .build());
        eventPublisher.publishEvent(new DomainLimitRequested(saved.getId(), owner.getId(), limit, requestedTotal));
        return saved;
    }

    private int requireRequestable(final long userId, final int requestedTotal) {
        final var limit = customDomainLimits.limitFor(userId);
        if (limit >= DomainAllowanceDto.MAX_DOMAINS) {
            log.info("Custom domain limit request refused at the highest limit userId={}", userId);
            throw new CustomDomainConflictException(AT_THE_MOST);
        }
        if (customDomainRequestRepository.existsByUser_IdAndStatus(userId, DomainRequestStatus.WAITING)) {
            log.info("Custom domain limit request refused, one is already waiting userId={}", userId);
            throw new CustomDomainConflictException(ALREADY_WAITING);
        }
        if (customDomainLimits.usedBy(userId) < limit) {
            log.info("Custom domain limit request refused below the limit userId={} limit={}", userId, limit);
            throw new CustomDomainConflictException(BELOW_THE_LIMIT);
        }
        if (requestedTotal <= limit) {
            throw new FieldValidationException(REQUESTED_TOTAL, ABOVE_THE_LIMIT.formatted(limit));
        }
        return limit;
    }

    private void requireWithinRequestLimit(final User user) {
        final var limitKey = REQUEST_LIMIT + user.getId();
        if (!withinRequestLimit(user, limitKey)) {
            log.info("Custom domain limit request refused by the rate limit userId={}", user.getId());
            throw new RateLimitExceededException(TOO_MANY_REQUESTS, rateLimiter.resetsIn(limitKey).orElse(REQUEST_WINDOW));
        }
    }

    private boolean withinRequestLimit(final User user, final String limitKey) {
        try {
            return rateLimiter.tryAcquire(limitKey, REQUESTS_PER_USER, REQUEST_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Custom domain limit request refused, the rate limit is unavailable userId={}", user.getId());
            throw new ServiceBusyException(REQUESTS_PAUSED, LIMITER_RETRY);
        }
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
            log.info("Custom domain limit request refused for a deactivated account userId={}", user.getId());
            throw new DisabledException(ACCOUNT_DEACTIVATED);
        }
    }

}
