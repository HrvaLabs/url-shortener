package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.AdminDomainResponse;
import cc.hrva.urlshortener.dto.domain.CloudflareCheckResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.DomainSummaryResponse;
import cc.hrva.urlshortener.dto.domain.ReconcileResponse;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.specification.CustomDomainSpecification;
import cc.hrva.urlshortener.service.CustomDomainAdminService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.domain.AdminDomainResponses;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainModeration;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval.Actor;
import cc.hrva.urlshortener.service.domain.CustomDomainSetup;
import cc.hrva.urlshortener.util.SortWhitelist;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
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
public class DefaultCustomDomainAdminService implements CustomDomainAdminService {

    private static final String NOT_FOUND = "Custom domain not found";
    private static final String USER_NOT_FOUND = "User not found";
    private static final String ALREADY_REVIEWED = "This application was already reviewed.";
    private static final String OWNER_DEACTIVATED = "This account is deactivated, so its domain can't be approved.";
    private static final String RECONCILE_RUNNING = "A reconcile is already running. Try again in a minute.";
    private static final String BLOCKED_BY_RULE = "This domain matches a block rule.";
    private static final String NOTE = "note";
    private static final String HTTPS = "https://";
    private static final String NOTE_REQUIRED = "Reason is required";
    private static final String CREATED_AT = "createdAt";
    private static final SortWhitelist DOMAIN_SORTS = SortWhitelist.of(CREATED_AT, "hostname", "status")
            .byDefault(Sort.by(Sort.Order.desc(CREATED_AT)))
            .thenBy(Sort.Order.desc(CREATED_AT), Sort.Order.desc("id"));

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainAllowanceRepository customDomainAllowanceRepository;
    private final CustomDomainReconcileRepository customDomainReconcileRepository;
    private final UserRepository userRepository;
    private final CustomDomainCapLock customDomainCapLock;
    private final CloudflareTokenCheck cloudflareTokenCheck;
    private final CustomDomainProvisioner customDomainProvisioner;
    private final CustomDomainSetup customDomainSetup;
    private final CustomDomainModeration customDomainModeration;
    private final CustomDomainRemoval customDomainRemoval;
    private final CustomDomainReconciler customDomainReconciler;
    private final AdminDomainResponses adminDomainResponses;
    private final LinkCheckService linkCheckService;
    private final SendingEmailService sendingEmailService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    public Page<AdminDomainResponse> list(final CustomDomainStatus status, final String search, final Pageable pageable) {
        final var sorted = DOMAIN_SORTS.apply(pageable);
        var spec = Specification.<CustomDomain>unrestricted();
        if (status != null) {
            spec = spec.and(CustomDomainSpecification.hasStatus(status));
        }
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(CustomDomainSpecification.searchMatches(search.strip()));
        }
        final var domains = customDomainRepository.findAll(spec, sorted);
        return new PageImpl<>(adminDomainResponses.of(domains.getContent()), domains.getPageable(), domains.getTotalElements());
    }

    @Override
    public DomainSummaryResponse summary() {
        final var settings = appProperties.getDomains();
        final var token = cloudflareTokenCheck.current();
        final var reconcile = customDomainReconcileRepository.newest().orElse(null);
        return new DomainSummaryResponse(
                customDomainRepository.countSlotsInUse(),
                settings.getMaxActive(),
                settings.getFreeAllowance(),
                newestTotal(token, reconcile),
                customDomainReconciler.lastQuota()
                        .map(quota -> new DomainSummaryResponse.CloudflareQuota(quota.used(), quota.allocated()))
                        .orElse(null),
                customDomainRepository.countByStatus(CustomDomainStatus.PENDING_REVIEW),
                customDomainRepository.countByStatus(CustomDomainStatus.ACTIVE),
                CloudflareCheckResponse.of(token),
                reconcile == null ? null : ReconcileResponse.of(reconcile, settings.getIgnoredHostnames()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainResponse approve(final long id, final String note, final String performedBy) {
        final var application = customDomainRepository.findById(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        requirePending(application);
        requireNoBlockRule(application);
        final var cloudflareTotal = customDomainSetup.liveTotal(id);
        final var approved = Objects.requireNonNull(transactionOperations.execute(status ->
                approveWithinTheCap(id, StringUtils.stripToNull(note), performedBy, cloudflareTotal)));
        handOver(id);
        log.info("Custom domain approved domainId={} cloudflareTotal={}", id, cloudflareTotal);
        tellOwner(id, approved, owner -> sendingEmailService.sendDomainApprovedEmail(owner, approved.domain().hostname(), approved.domain().dnsRecord()));
        return approved;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainResponse reject(final long id, final String note, final String performedBy) {
        final var reviewNote = StringUtils.stripToNull(note);
        final var rejected = Objects.requireNonNull(transactionOperations.execute(status -> rejectPending(id, reviewNote, performedBy)));
        tellOwner(id, rejected, owner -> sendingEmailService.sendDomainRejectedEmail(owner, rejected.domain().hostname(), reviewNote));
        return rejected;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainResponse retry(final long id) {
        final var domain = Objects.requireNonNull(transactionOperations.execute(transaction -> customDomainRepository.findById(id)))
                .orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        customDomainSetup.retry(domain);
        return transactionOperations.execute(transaction -> reread(id));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainResponse disable(final long id, final String note) {
        final var reason = StringUtils.strip(note);
        if (StringUtils.isEmpty(reason)) {
            throw new FieldValidationException(NOTE, NOTE_REQUIRED);
        }
        customDomainModeration.disable(id, DomainDisableReason.ADMIN, reason);
        return transactionOperations.execute(transaction -> reread(id));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainResponse enable(final long id) {
        customDomainModeration.enable(id);
        return transactionOperations.execute(transaction -> reread(id));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DomainRemovalResponse delete(final long id, final DomainLinkMode mode, final String performedBy) {
        return customDomainRemoval.remove(id, mode, new Actor(performedBy, true));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReconcileResponse reconcile() {
        final var run = customDomainReconciler.reconcile()
                .or(customDomainReconcileRepository::newest)
                .orElseThrow(() -> new CustomDomainConflictException(RECONCILE_RUNNING));
        return ReconcileResponse.of(run, appProperties.getDomains().getIgnoredHostnames());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CloudflareCheckResponse checkCloudflare() {
        return CloudflareCheckResponse.of(cloudflareTokenCheck.check());
    }

    @Override
    public DomainAllowanceResponse allowance(final long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(USER_NOT_FOUND);
        }
        return customDomainAllowanceRepository.findById(userId)
                .map(DomainAllowanceResponse::of)
                .orElseGet(() -> DomainAllowanceResponse.byDefault(userId, appProperties.getDomains().getPerUser()));
    }

    @Override
    @Transactional
    public DomainAllowanceResponse setAllowance(final long userId, final DomainAllowanceDto domainAllowanceDto, final String performedBy) {
        lockAccount(userId);
        final var allowance = customDomainAllowanceRepository.findById(userId)
                .orElseGet(() -> CustomDomainAllowance.builder().userId(userId).build());
        allowance.setMaxDomains(domainAllowanceDto.getMaxDomains());
        allowance.setReason(domainAllowanceDto.getReason());
        allowance.setGrantedBy(performedBy);
        allowance.setGrantedAt(LocalDateTime.now(clock));
        final var saved = customDomainAllowanceRepository.saveAndFlush(allowance);
        log.info("Custom domain allowance set userId={} maxDomains={}", userId, saved.getMaxDomains());
        return DomainAllowanceResponse.of(saved);
    }

    @Override
    @Transactional
    public void removeAllowance(final long userId) {
        lockAccount(userId);
        customDomainAllowanceRepository.findById(userId).ifPresent(allowance -> {
            customDomainAllowanceRepository.delete(allowance);
            log.info("Custom domain allowance removed userId={}", userId);
        });
    }

    private AdminDomainResponse approveWithinTheCap(final long id, final String note, final String performedBy, final int cloudflareTotal) {
        customDomainCapLock.lock();
        final var domain = customDomainRepository.findWithOwner(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        requirePending(domain);
        requireNoBlockRule(domain);
        final var owner = domain.getOwner();
        if (owner == null || Boolean.FALSE.equals(owner.getActive())) {
            log.info("Custom domain approval refused for a deactivated or deleted account domainId={}", id);
            throw new CustomDomainConflictException(OWNER_DEACTIVATED);
        }
        customDomainSetup.requireRoom(id, cloudflareTotal, false);
        if (customDomainRepository.markApproved(id, performedBy, note, LocalDateTime.now(clock)) != 1) {
            log.info("Custom domain application was reviewed before its approval domainId={}", id);
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        return reread(id);
    }

    private AdminDomainResponse rejectPending(final long id, final String note, final String performedBy) {
        requirePending(customDomainRepository.findById(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND)));
        if (customDomainRepository.markRejected(id, performedBy, note, LocalDateTime.now(clock)) != 1) {
            log.info("Custom domain application was reviewed before its rejection domainId={}", id);
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        log.info("Custom domain rejected domainId={}", id);
        return reread(id);
    }

    private static void tellOwner(final long id, final AdminDomainResponse reviewed, final Consumer<String> send) {
        if (reviewed.owner() == null) {
            return;
        }
        try {
            send.accept(reviewed.owner().email());
        } catch (final RuntimeException exception) {
            log.warn("Telling the owner about the review of their custom domain failed domainId={} exception={}", id, exception.getClass().getName());
        }
    }

    private void handOver(final long id) {
        try {
            customDomainProvisioner.provisionAsync(id);
        } catch (final RuntimeException exception) {
            log.warn("Custom domain provisioning couldn't start after its approval domainId={} exception={}", id, exception.getClass().getName());
        }
    }

    private static void requirePending(final CustomDomain domain) {
        if (domain.getStatus() != CustomDomainStatus.PENDING_REVIEW) {
            log.info("Custom domain review refused, it was already reviewed domainId={} status={}", domain.getId(), domain.getStatus());
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
    }

    private void requireNoBlockRule(final CustomDomain domain) {
        if (linkCheckService.isBlockedByRule(HTTPS + domain.getHostname() + "/")) {
            log.info("Custom domain approval refused, the domain matches a block rule domainId={}", domain.getId());
            throw new CustomDomainConflictException(BLOCKED_BY_RULE);
        }
    }

    private AdminDomainResponse reread(final long id) {
        return adminDomainResponses.of(customDomainRepository.findWithOwner(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND)));
    }

    private void lockAccount(final long userId) {
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND));
    }

    private static Integer newestTotal(final TokenState token, final ReconcileRun reconcile) {
        final var checked = token.checkedAt() != null && token.cloudflareTotal() != null;
        final var reconciled = reconcile != null && reconcile.cloudflareTotal() != null;
        if (checked && reconciled) {
            return reconcile.ranAt().isAfter(token.checkedAt()) ? reconcile.cloudflareTotal() : token.cloudflareTotal();
        }
        if (checked) {
            return token.cloudflareTotal();
        }
        return reconciled ? reconcile.cloudflareTotal() : null;
    }

}
