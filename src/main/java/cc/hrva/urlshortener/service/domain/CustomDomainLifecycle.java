package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareHostname;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainNeedsAttention;
import cc.hrva.urlshortener.service.domain.CloudflareStatusMapping.Transition;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainLifecycle {

    private static final int BATCH_SIZE = 50;
    private static final Duration FAILED_CHECK_WAIT = Duration.ofHours(1);
    private static final List<CustomDomainStatus> CHECK_ORDER = List.of(CustomDomainStatus.AWAITING_DNS, CustomDomainStatus.ERROR,
            CustomDomainStatus.ACTIVE);
    private static final Set<DomainProblem> ALERTED = EnumSet.of(DomainProblem.PROVISIONING_FAILED, DomainProblem.CLOUDFLARE_BLOCKED,
            DomainProblem.CNAME_REMOVED);

    private final CustomDomainRepository customDomainRepository;
    private final CloudflareClient cloudflareClient;
    private final CustomDomainProvisioner customDomainProvisioner;
    private final CustomDomainHostnames customDomainHostnames;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runInBackground() {
        runOnce();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LifecycleRun runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.info("Custom domain lifecycle skipped: the previous run is still going");
            return LifecycleRun.NOTHING;
        }
        try {
            final var tally = new Tally();
            due(CustomDomainStatus.APPROVED_PROVISIONING, BATCH_SIZE)
                    .forEach(id -> tally.provisioned(safely("provision", id, () -> customDomainProvisioner.provision(id))));
            for (final var status : CHECK_ORDER) {
                final var left = BATCH_SIZE - tally.attemptedChecks();
                if (left > 0) {
                    due(status, left).forEach(id -> tally.checked(safely("check", id, () -> checkNow(id))));
                }
            }
            Objects.requireNonNull(transactionOperations.execute(status -> customDomainRepository.findDeletePendingIds(Limit.of(BATCH_SIZE))))
                    .forEach(id -> tally.deleted(safely("delete", id, () -> deleteNow(id))));
            final var run = tally.run();
            if (run.equals(LifecycleRun.NOTHING)) {
                log.debug("Custom domain lifecycle found nothing to do");
            } else {
                log.info("Custom domain lifecycle provisioned={} checked={} deleted={} failed={}", run.provisioned(), run.checked(), run.deleted(),
                        run.failed());
            }
            return run;
        } finally {
            running.set(false);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LifecycleOutcome checkNow(final long id) {
        final var domain = fresh(id);
        if (!checkable(domain)) {
            log.debug("Custom domain not checked, there is nothing to check domainId={}", id);
            return LifecycleOutcome.SKIPPED;
        }
        if (domain.getCloudflareId() == null) {
            return record(domain, CloudflareStatusMapping.gone(), null, now());
        }
        final var result = cloudflareClient.get(domain.getCloudflareId());
        final var now = now();
        return switch (result) {
            case CloudflareResult.Ok<CloudflareHostname>(final var hostname, final var ignored) -> record(domain, CloudflareStatusMapping.next(
                    domain.getStatus(), domain.getActivatedAt() != null, hostname, approvedAt(domain), now), hostname, now);
            case CloudflareResult.NotFound<CloudflareHostname> notFound -> record(domain, CloudflareStatusMapping.gone(), null, now);
            case CloudflareResult.Refused<CloudflareHostname> refused -> postpone(domain, now);
            case CloudflareResult.Unreachable<CloudflareHostname> unreachable -> postpone(domain, now);
        };
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LifecycleOutcome deleteNow(final long id) {
        final var domain = fresh(id);
        if (domain == null || !domain.isCloudflareDeletePending()) {
            log.debug("Custom domain not deleted in Cloudflare, nothing is queued domainId={}", id);
            return LifecycleOutcome.SKIPPED;
        }
        if (domain.getCloudflareId() == null || customDomainHostnames.isIgnored(domain.getHostname())) {
            log.info("Custom domain hostname kept in Cloudflare domainId={} hostname={}", id, domain.getHostname());
            return finishDelete(id);
        }
        final var result = cloudflareClient.delete(domain.getCloudflareId());
        if (result instanceof CloudflareResult.Ok<Void> || result instanceof CloudflareResult.NotFound<Void>) {
            log.info("Custom domain deleted in Cloudflare domainId={}", id);
            return finishDelete(id);
        }
        log.info("Custom domain delete in Cloudflare failed, trying again next run domainId={}", id);
        return LifecycleOutcome.FAILED;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordMissing(final long id, final CustomDomainStatus expectedStatus) {
        final var gone = CloudflareStatusMapping.gone();
        final var now = now();
        final var written = transactionOperations.execute(status -> {
            final var updated = customDomainRepository.recordCheck(id, expectedStatus, gone.status(), null, null, gone.problem(), gone.lastError(),
                    null, null, now);
            if (updated == 1 && expectedStatus != gone.status()) {
                eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
            }
            return updated;
        });
        log.info("Custom domain missing in Cloudflare domainId={} from={} recorded={}", id, expectedStatus, Objects.equals(written, 1));
    }

    private LifecycleOutcome record(final CustomDomain domain, final Transition transition, final CloudflareHostname hostname,
                                    final LocalDateTime now) {
        final var next = DomainCheckSchedule.nextCheck(transition.status(), transition.problem(), approvedAt(domain), domain.getCheckCount() + 1,
                now);
        final var settled = transition.status() == CustomDomainStatus.AWAITING_DNS && next == null
                ? new Transition(CustomDomainStatus.ERROR, DomainProblem.DNS_TIMED_OUT, transition.lastError())
                : transition;
        final var activatedAt = settled.status() == CustomDomainStatus.ACTIVE ? now : null;
        final var written = transactionOperations.execute(status -> {
            final var updated = customDomainRepository.recordCheck(domain.getId(), domain.getStatus(), settled.status(),
                    hostname == null ? null : hostname.status(), hostname == null ? null : hostname.sslStatus(), settled.problem(),
                    settled.lastError(), activatedAt, next, now);
            if (updated == 1) {
                announce(domain, settled);
            }
            return updated;
        });
        if (!Objects.equals(written, 1)) {
            log.info("Custom domain changed while it was checked domainId={}", domain.getId());
        } else if (domain.getStatus() != settled.status() || domain.getProblem() != settled.problem()) {
            log.info("Custom domain checked domainId={} from={} to={} problem={}", domain.getId(), domain.getStatus(), settled.status(),
                    settled.problem());
        }
        return LifecycleOutcome.DONE;
    }

    private void announce(final CustomDomain domain, final Transition transition) {
        if (domain.getStatus() != transition.status()) {
            eventPublisher.publishEvent(new CustomDomainChangedEvent(domain.getId()));
        }
        final var newProblem = domain.getStatus() != CustomDomainStatus.ERROR || domain.getProblem() != transition.problem();
        if (transition.status() == CustomDomainStatus.ERROR && ALERTED.contains(transition.problem()) && newProblem) {
            eventPublisher.publishEvent(new DomainNeedsAttention(domain.getId(), domain.getHostname(), transition.problem()));
        }
    }

    private LifecycleOutcome postpone(final CustomDomain domain, final LocalDateTime now) {
        final var scheduled = DomainCheckSchedule.nextCheck(domain.getStatus(), domain.getProblem(), approvedAt(domain), domain.getCheckCount(), now);
        final var latest = now.plus(FAILED_CHECK_WAIT);
        final LocalDateTime next;
        if (scheduled == null) {
            next = domain.getStatus() == CustomDomainStatus.ERROR ? null : latest;
        } else {
            next = ObjectUtils.min(scheduled, latest);
        }
        customDomainRepository.postponeCheck(domain.getId(), domain.getStatus(), next, now);
        log.info("Custom domain check failed, trying again later domainId={} nextCheckAt={}", domain.getId(), next);
        return LifecycleOutcome.FAILED;
    }

    private LifecycleOutcome finishDelete(final long id) {
        customDomainRepository.clearDeletePending(id, now());
        try {
            if (customDomainRepository.deleteOrphan(id) == 1) {
                log.info("Custom domain of a deleted account removed domainId={}", id);
            }
        } catch (final DataIntegrityViolationException exception) {
            log.warn("Custom domain of a deleted account couldn't be removed yet domainId={} exception={}", id, exception.getClass().getName());
        }
        return LifecycleOutcome.DONE;
    }

    private List<Long> due(final CustomDomainStatus status, final int limit) {
        return Objects.requireNonNull(transactionOperations.execute(transaction -> customDomainRepository.findDueIds(status, now(),
                Limit.of(limit))));
    }

    private CustomDomain fresh(final long id) {
        return transactionOperations.execute(status -> customDomainRepository.findById(id).orElse(null));
    }

    private static boolean checkable(final CustomDomain domain) {
        if (domain == null) {
            return false;
        }
        return switch (domain.getStatus()) {
            case AWAITING_DNS, ACTIVE -> true;
            case ERROR -> domain.getProblem() != DomainProblem.PROVISIONING_FAILED;
            case PENDING_REVIEW, APPROVED_PROVISIONING, REJECTED, DISABLED, DELETED -> false;
        };
    }

    private static LifecycleOutcome safely(final String step, final long id, final Supplier<LifecycleOutcome> action) {
        try {
            return action.get();
        } catch (final RuntimeException exception) {
            log.warn("Custom domain {} failed domainId={} exception={}", step, id, exception.getClass().getName());
            return LifecycleOutcome.FAILED;
        }
    }

    private static LocalDateTime approvedAt(final CustomDomain domain) {
        return ObjectUtils.firstNonNull(domain.getReviewedAt(), domain.getCreatedAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record LifecycleRun(int provisioned, int checked, int deleted, int failed) {

        static final LifecycleRun NOTHING = new LifecycleRun(0, 0, 0, 0);

    }

    private static final class Tally {

        private int provisioned;
        private int checked;
        private int deleted;
        private int failed;
        private int attemptedChecks;

        private void provisioned(final LifecycleOutcome outcome) {
            provisioned += count(outcome);
        }

        private void checked(final LifecycleOutcome outcome) {
            attemptedChecks++;
            checked += count(outcome);
        }

        private void deleted(final LifecycleOutcome outcome) {
            deleted += count(outcome);
        }

        private int attemptedChecks() {
            return attemptedChecks;
        }

        private int count(final LifecycleOutcome outcome) {
            if (outcome == LifecycleOutcome.FAILED) {
                failed++;
            }
            return outcome == LifecycleOutcome.DONE ? 1 : 0;
        }

        private LifecycleRun run() {
            return new LifecycleRun(provisioned, checked, deleted, failed);
        }

    }

}
