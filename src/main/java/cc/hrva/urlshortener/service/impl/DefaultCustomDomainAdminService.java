package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.AdminDomainResponse;
import cc.hrva.urlshortener.dto.domain.CloudflareCheckResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceResponse;
import cc.hrva.urlshortener.dto.domain.DomainSummaryResponse;
import cc.hrva.urlshortener.dto.domain.ReconcileResponse;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.specification.CustomDomainSpecification;
import cc.hrva.urlshortener.service.CustomDomainAdminService;
import cc.hrva.urlshortener.service.domain.AdminDomainResponses;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
    private static final String CAP_REACHED = "Cap reached: %d of %d custom domains are in use. Delete one or raise the cap (APP_DOMAINS_MAX_ACTIVE).";
    private static final String FREE_ALLOWANCE_USED = "Cloudflare already has %d custom hostnames and %d are free. Delete one here or in Cloudflare first.";
    private static final String NOT_SET_UP = "Cloudflare isn't set up: %s. Check CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID.";
    private static final String NOTHING_TO_RETRY = "There's nothing to retry for this domain.";
    private static final String RECONCILE_RUNNING = "A reconcile is already running. Try again in a minute.";
    private static final Map<CloudflareTokenStatus, String> TOKEN_PROBLEMS = Map.of(
            CloudflareTokenStatus.MISSING, "the API token is missing",
            CloudflareTokenStatus.INVALID, "the API token is invalid",
            CloudflareTokenStatus.NO_PERMISSION, "the API token can't manage custom hostnames",
            CloudflareTokenStatus.UNREACHABLE, "Cloudflare can't be reached");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainAllowanceRepository customDomainAllowanceRepository;
    private final CustomDomainReconcileRepository customDomainReconcileRepository;
    private final UserRepository userRepository;
    private final CustomDomainCapLock customDomainCapLock;
    private final CloudflareTokenCheck cloudflareTokenCheck;
    private final CustomDomainProvisioner customDomainProvisioner;
    private final CustomDomainLifecycle customDomainLifecycle;
    private final CustomDomainReconciler customDomainReconciler;
    private final AdminDomainResponses adminDomainResponses;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    public Page<AdminDomainResponse> list(final CustomDomainStatus status, final String search, final Pageable pageable) {
        var spec = Specification.<CustomDomain>unrestricted();
        if (status != null) {
            spec = spec.and(CustomDomainSpecification.hasStatus(status));
        }
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(CustomDomainSpecification.searchMatches(search.strip()));
        }
        final var newestFirst = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST)
                : Pageable.unpaged(NEWEST_FIRST);
        final var domains = customDomainRepository.findAll(spec, newestFirst);
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
        requirePending(customDomainRepository.findById(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND)));
        final var cloudflareTotal = liveTotal(cloudflareTokenCheck.check(), id);
        final var approved = transactionOperations.execute(status -> approveWithinTheCap(id, StringUtils.stripToNull(note), performedBy, cloudflareTotal));
        handOver(id);
        log.info("Custom domain approved domainId={} cloudflareTotal={}", id, cloudflareTotal);
        return approved;
    }

    @Override
    @Transactional
    public AdminDomainResponse reject(final long id, final String note, final String performedBy) {
        requirePending(customDomainRepository.findById(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND)));
        if (customDomainRepository.markRejected(id, performedBy, StringUtils.stripToNull(note), LocalDateTime.now(clock)) != 1) {
            log.info("Custom domain application was reviewed before its rejection domainId={}", id);
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        log.info("Custom domain rejected domainId={}", id);
        return reread(id);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDomainResponse retry(final long id) {
        final var domain = Objects.requireNonNull(transactionOperations.execute(transaction -> customDomainRepository.findById(id)))
                .orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        final var status = domain.getStatus();
        final var setupFailed = domain.getProblem() == DomainProblem.PROVISIONING_FAILED;
        if (status == CustomDomainStatus.APPROVED_PROVISIONING && setupFailed) {
            customDomainProvisioner.provision(id);
        } else if (status == CustomDomainStatus.ERROR && setupFailed) {
            provisionAgain(domain);
        } else if (status == CustomDomainStatus.ERROR || status == CustomDomainStatus.AWAITING_DNS) {
            customDomainLifecycle.checkNow(id);
        } else {
            log.info("Custom domain retry refused, there is nothing to retry domainId={} status={}", id, status);
            throw new CustomDomainConflictException(NOTHING_TO_RETRY);
        }
        log.info("Custom domain retried domainId={} status={} problem={}", id, status, domain.getProblem());
        return transactionOperations.execute(transaction -> reread(id));
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

    private Integer liveTotal(final TokenState token, final long id) {
        if (token.status() != CloudflareTokenStatus.OK) {
            log.info("Custom domain setup refused, the Cloudflare token isn't usable domainId={} status={}", id, token.status());
            throw new CustomDomainConflictException(NOT_SET_UP.formatted(TOKEN_PROBLEMS.get(token.status())));
        }
        if (token.cloudflareTotal() == null) {
            log.info("Custom domain setup refused, Cloudflare gave no total domainId={}", id);
            throw new CustomDomainConflictException(NOT_SET_UP.formatted(TOKEN_PROBLEMS.get(CloudflareTokenStatus.UNREACHABLE)));
        }
        return token.cloudflareTotal();
    }

    private AdminDomainResponse approveWithinTheCap(final long id, final String note, final String performedBy, final int cloudflareTotal) {
        customDomainCapLock.lock();
        final var domain = customDomainRepository.findWithOwner(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        requirePending(domain);
        final var owner = domain.getOwner();
        if (owner == null || Boolean.FALSE.equals(owner.getActive())) {
            log.info("Custom domain approval refused for a deactivated or deleted account domainId={}", id);
            throw new CustomDomainConflictException(OWNER_DEACTIVATED);
        }
        requireRoom(id, cloudflareTotal, false);
        if (customDomainRepository.markApproved(id, performedBy, note, LocalDateTime.now(clock)) != 1) {
            log.info("Custom domain application was reviewed before its approval domainId={}", id);
            throw new CustomDomainConflictException(ALREADY_REVIEWED);
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        return reread(id);
    }

    private void requireRoom(final long id, final int cloudflareTotal, final boolean holdsASlot) {
        final var settings = appProperties.getDomains();
        final var slotsInUse = customDomainRepository.countSlotsInUse() - (holdsASlot ? 1 : 0);
        if (slotsInUse >= settings.getMaxActive()) {
            log.info("Custom domain refused at the cap domainId={} slotsInUse={} maxActive={}", id, slotsInUse, settings.getMaxActive());
            throw new CustomDomainConflictException(CAP_REACHED.formatted(slotsInUse, settings.getMaxActive()));
        }
        final var notYetInCloudflare = customDomainRepository.countByStatus(CustomDomainStatus.APPROVED_PROVISIONING);
        final var hostnames = cloudflareTotal + notYetInCloudflare;
        if (hostnames >= settings.getFreeAllowance()) {
            log.info("Custom domain refused at the free allowance domainId={} cloudflareTotal={} notYetInCloudflare={} freeAllowance={}", id,
                    cloudflareTotal, notYetInCloudflare, settings.getFreeAllowance());
            throw new CustomDomainConflictException(FREE_ALLOWANCE_USED.formatted(hostnames, settings.getFreeAllowance()));
        }
    }

    private void provisionAgain(final CustomDomain domain) {
        final var id = domain.getId();
        final int cloudflareTotal = liveTotal(cloudflareTokenCheck.check(), id);
        transactionOperations.executeWithoutResult(transaction -> {
            customDomainCapLock.lock();
            requireRoom(id, cloudflareTotal, domain.getCloudflareId() != null);
            if (customDomainRepository.markRetrying(id, LocalDateTime.now(clock)) != 1) {
                log.info("Custom domain changed before its retry domainId={}", id);
                throw new CustomDomainConflictException(NOTHING_TO_RETRY);
            }
            eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        });
        customDomainProvisioner.provision(id);
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
