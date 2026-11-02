package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.domain.AdminDomainRequestResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.CustomDomainRequest;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.specification.CustomDomainRequestSpecification;
import cc.hrva.urlshortener.service.CustomDomainAdminService;
import cc.hrva.urlshortener.service.CustomDomainRequestAdminService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.domain.AdminDomainRequestResponses;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.util.SortWhitelist;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultCustomDomainRequestAdminService implements CustomDomainRequestAdminService {

    private static final String NOT_FOUND = "Request not found";
    private static final String USER_NOT_FOUND = "User not found";
    private static final String ALREADY_REVIEWED = "This request was already reviewed.";
    private static final String OWNER_DEACTIVATED = "This account is deactivated, so its request can't be approved.";
    private static final String ALREADY_HAS_THE_LIMIT = "This account's limit is already %d. Decline the request instead.";
    private static final String CREATED_AT = "createdAt";
    private static final SortWhitelist REQUEST_SORTS = SortWhitelist.of(CREATED_AT, "requestedTotal", "status")
            .byDefault(Sort.by(Sort.Order.desc(CREATED_AT)))
            .thenBy(Sort.Order.desc(CREATED_AT), Sort.Order.desc("id"));

    private final CustomDomainRequestRepository customDomainRequestRepository;
    private final UserRepository userRepository;
    private final CustomDomainAdminService customDomainAdminService;
    private final CustomDomainLimits customDomainLimits;
    private final AdminDomainRequestResponses adminDomainRequestResponses;
    private final SendingEmailService sendingEmailService;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    @Override
    public Page<AdminDomainRequestResponse> list(final DomainRequestStatus status, final String search, final Pageable pageable) {
        final var sorted = REQUEST_SORTS.apply(pageable);
        var spec = Specification.<CustomDomainRequest>unrestricted();
        if (status != null) {
            spec = spec.and(CustomDomainRequestSpecification.hasStatus(status));
        }
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(CustomDomainRequestSpecification.searchMatches(search.strip()));
        }
        final var requests = customDomainRequestRepository.findAll(spec, sorted);
        return new PageImpl<>(adminDomainRequestResponses.of(requests.getContent()), requests.getPageable(), requests.getTotalElements());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainRequestResponse approve(final long id, final String note, final String performedBy) {
        final var adminNote = StringUtils.stripToNull(note);
        final var approved = Objects.requireNonNull(transactionOperations.execute(status -> approveWaiting(id, adminNote, performedBy)));
        tellOwner(id, approved, email -> sendingEmailService.sendDomainRequestApprovedEmail(email, approved.requestedTotal(), adminNote));
        return approved;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainRequestResponse decline(final long id, final String note, final String performedBy) {
        final var adminNote = StringUtils.stripToNull(note);
        final var declined = Objects.requireNonNull(transactionOperations.execute(status -> declineWaiting(id, adminNote, performedBy)));
        tellOwner(id, declined, email -> sendingEmailService.sendDomainRequestDeclinedEmail(email, declined.requestedTotal(), declined.currentLimit(),
                adminNote));
        return declined;
    }

    private AdminDomainRequestResponse approveWaiting(final long id, final String note, final String performedBy) {
        final var request = waiting(id);
        final var userId = request.getUser().getId();
        final var requestedTotal = request.getRequestedTotal();
        final var reason = request.getReason();
        final var owner = userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND));
        if (Boolean.FALSE.equals(owner.getActive())) {
            log.info("Custom domain limit request approval refused for a deactivated account requestId={}", id);
            throw new CustomDomainConflictException(OWNER_DEACTIVATED);
        }
        final var limit = customDomainLimits.limitFor(userId);
        if (limit >= requestedTotal) {
            log.info("Custom domain limit request approval refused, the limit is already as high requestId={} limit={}", id, limit);
            throw new CustomDomainConflictException(ALREADY_HAS_THE_LIMIT.formatted(limit));
        }
        markReviewed(id, DomainRequestStatus.APPROVED, performedBy, note);
        customDomainAdminService.setAllowance(userId, new DomainAllowanceDto(requestedTotal, reason), performedBy);
        log.info("Custom domain limit request approved requestId={} userId={} maxDomains={}", id, userId, requestedTotal);
        return reread(id);
    }

    private AdminDomainRequestResponse declineWaiting(final long id, final String note, final String performedBy) {
        waiting(id);
        markReviewed(id, DomainRequestStatus.DECLINED, performedBy, note);
        log.info("Custom domain limit request declined requestId={}", id);
        return reread(id);
    }

    private CustomDomainRequest waiting(final long id) {
        final var request = customDomainRequestRepository.findWithUser(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        if (request.getStatus() != DomainRequestStatus.WAITING) {
            log.info("Custom domain limit request review refused, it was already reviewed requestId={} status={}", id, request.getStatus());
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
        return request;
    }

    private void markReviewed(final long id, final DomainRequestStatus status, final String performedBy, final String note) {
        if (customDomainRequestRepository.markReviewed(id, status, performedBy, note, LocalDateTime.now(clock)) != 1) {
            log.info("Custom domain limit request was reviewed in the meantime requestId={}", id);
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
    }

    private AdminDomainRequestResponse reread(final long id) {
        return adminDomainRequestResponses.of(customDomainRequestRepository.findWithUser(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND)));
    }

    private static void tellOwner(final long id, final AdminDomainRequestResponse reviewed, final Consumer<String> send) {
        if (reviewed.owner() == null) {
            return;
        }
        try {
            send.accept(reviewed.owner().email());
        } catch (final RuntimeException exception) {
            log.warn("Telling the owner about the review of their custom domain limit request failed requestId={} exception={}", id,
                    exception.getClass().getName());
        }
    }

}
