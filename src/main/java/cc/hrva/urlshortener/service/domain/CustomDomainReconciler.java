package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareHostname;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.MissingDomain;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainInCloudflare;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainDrift;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainReconciler {

    private static final int PAGE_SIZE = 50;
    private static final String HTTPS = "https://";
    private static final int MAX_PAGES = 1000;
    private static final Duration STALE_AFTER = Duration.ofHours(26);
    private static final Duration RUNS_KEPT = Duration.ofDays(30);
    private static final Set<CustomDomainStatus> HOLDING_A_HOSTNAME = EnumSet.of(CustomDomainStatus.APPROVED_PROVISIONING,
            CustomDomainStatus.AWAITING_DNS, CustomDomainStatus.ACTIVE, CustomDomainStatus.DISABLED, CustomDomainStatus.ERROR);
    private static final Set<CustomDomainStatus> NOT_EXPECTED_IN_CLOUDFLARE = EnumSet.of(CustomDomainStatus.APPROVED_PROVISIONING,
            CustomDomainStatus.DELETED);
    private static final Set<CustomDomainStatus> MARKED_WHEN_MISSING = EnumSet.of(CustomDomainStatus.AWAITING_DNS, CustomDomainStatus.ACTIVE,
            CustomDomainStatus.ERROR);

    private final CloudflareClient cloudflareClient;
    private final CloudflareTokenCheck cloudflareTokenCheck;
    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainReconcileRepository customDomainReconcileRepository;
    private final CustomDomainLifecycle customDomainLifecycle;
    private final CustomDomainHostnames customDomainHostnames;
    private final LinkCheckService linkCheckService;
    private final CustomDomainModeration customDomainModeration;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final AppProperties appProperties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<QuotaReading> lastQuota = new AtomicReference<>();

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcileInBackground() {
        reconcile();
        checkActiveHostnames();
    }

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void catchUpAtStartup() {
        try {
            final var lastRanAt = transactionOperations.execute(status -> customDomainReconcileRepository.newest()
                    .map(ReconcileRun::ranAt)
                    .orElse(null));
            if (lastRanAt != null && lastRanAt.isAfter(now().minus(STALE_AFTER))) {
                log.info("Custom domain reconcile startup catch-up not needed lastRanAt={}", lastRanAt);
                return;
            }
            log.info("Custom domain reconcile startup catch-up lastRanAt={}", lastRanAt);
            reconcile();
        } catch (final RuntimeException exception) {
            log.warn("Custom domain reconcile startup catch-up failed exception={}", exception.getClass().getName());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ReconcileRun> reconcile() {
        if (!running.compareAndSet(false, true)) {
            log.info("Custom domain reconcile skipped: a run is still going");
            return Optional.empty();
        }
        try {
            return Optional.of(run());
        } finally {
            running.set(false);
        }
    }

    public Optional<QuotaReading> lastQuota() {
        return Optional.ofNullable(lastQuota.get());
    }

    private ReconcileRun run() {
        final var token = cloudflareTokenCheck.check();
        if (token.status() != CloudflareTokenStatus.OK) {
            return recorded(failed(token.cloudflareTotal(), Objects.requireNonNullElse(token.message(), "The Cloudflare token isn't usable.")), null);
        }
        final var listing = listing();
        if (listing.error() != null) {
            return recorded(failed(token.cloudflareTotal(), listing.error()), null);
        }
        readQuota();
        final var alreadyMissing = Objects.requireNonNull(transactionOperations.execute(status -> customDomainReconcileRepository.newestCompleted()
                .map(previous -> previous.missingDomains().stream().map(MissingDomain::id).collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of())));
        final var ours = transactionOperations.execute(status -> customDomainRepository.findForReconcile(HOLDING_A_HOSTNAME));
        final var comparison = compare(listing.hostnames(), Objects.requireNonNull(ours), alreadyMissing);
        final var run = new ReconcileRun(now(), listing.total(), comparison.oursInCloudflare(), comparison.unknown(), comparison.missing(), null);
        final var drift = run.unknownHostnames().isEmpty() && comparison.newlyMissing().isEmpty()
                ? null
                : new DomainDrift(run.unknownHostnames(), comparison.newlyMissing(), run.cloudflareTotal(),
                        appProperties.getDomains().getFreeAllowance());
        return recorded(run, drift);
    }

    private void checkActiveHostnames() {
        final var active = Objects.requireNonNull(transactionOperations.execute(status -> customDomainRepository.findByStatusIn(
                EnumSet.of(CustomDomainStatus.ACTIVE))));
        var disabled = 0;
        for (final var domain : active) {
            try {
                if (disableWhenBlocked(domain.getId(), linkCheckService.check(HTTPS + domain.getHostname() + "/"))) {
                    disabled++;
                }
            } catch (final RuntimeException exception) {
                log.warn("Custom domain hostname check failed domainId={} exception={}", domain.getId(), exception.getClass().getName());
            }
        }
        log.info("Custom domain hostname check active={} disabled={}", active.size(), disabled);
    }

    private boolean disableWhenBlocked(final long id, final LinkVerdict verdict) {
        if (verdict.outcome() != LinkVerdict.Outcome.BLOCK) {
            return false;
        }
        final var reason = verdict.blockReason() == BlockReason.DOMAIN_RULE ? DomainDisableReason.DOMAIN_RULE : DomainDisableReason.BLOCKLIST;
        return customDomainModeration.disableIfStillOn(id, reason, verdict.blockDetail());
    }

    private Listing listing() {
        final var hostnames = new ArrayList<CloudflareHostname>();
        Integer total = null;
        for (var page = 1; page <= MAX_PAGES; page++) {
            final var result = cloudflareClient.list(page);
            if (!(result instanceof CloudflareResult.Ok<List<CloudflareHostname>>(final var found, final var pageTotal))) {
                return Listing.failed("Listing custom hostnames failed on page %d: %s".formatted(page, CloudflareStatusMapping.failureText(result)));
            }
            total = total == null ? pageTotal : total;
            hostnames.addAll(found);
            if (found.size() < PAGE_SIZE) {
                return new Listing(List.copyOf(hostnames), total, null);
            }
        }
        return Listing.failed("Listing custom hostnames stopped after %d pages".formatted(MAX_PAGES));
    }

    private void readQuota() {
        if (cloudflareClient.quota() instanceof CloudflareResult.Ok<CloudflareClient.Quota>(final var quota, final var ignored)) {
            lastQuota.set(new QuotaReading(quota.used(), quota.allocated(), now()));
        } else {
            log.info("Cloudflare custom hostname quota couldn't be read, keeping the last reading");
        }
    }

    private Comparison compare(final List<CloudflareHostname> hostnames, final List<DomainInCloudflare> ours, final Set<Long> alreadyMissing) {
        final var ourIds = ours.stream().map(DomainInCloudflare::cloudflareId).filter(Objects::nonNull).collect(Collectors.toSet());
        final var ourHostnames = ours.stream()
                .filter(domain -> domain.status() != CustomDomainStatus.DELETED || domain.deletePending())
                .map(domain -> key(domain.hostname()))
                .collect(Collectors.toSet());
        final var unknown = new TreeSet<String>();
        var ignored = 0;
        for (final var hostname : hostnames) {
            if (ourIds.contains(hostname.id()) || ourHostnames.contains(key(hostname.hostname()))) {
                continue;
            }
            if (customDomainHostnames.isIgnored(hostname.hostname())) {
                ignored++;
            } else {
                unknown.add(Objects.requireNonNullElse(hostname.hostname(), hostname.id()));
            }
        }
        final var listedIds = hostnames.stream().map(CloudflareHostname::id).collect(Collectors.toSet());
        final var missing = new ArrayList<MissingDomain>();
        final var newlyMissing = new ArrayList<String>();
        var oursInCloudflare = 0;
        for (final var domain : ours) {
            if (domain.cloudflareId() == null || NOT_EXPECTED_IN_CLOUDFLARE.contains(domain.status())) {
                continue;
            }
            if (listedIds.contains(domain.cloudflareId()) || !confirmedMissing(domain)) {
                oursInCloudflare++;
            } else {
                missing.add(new MissingDomain(domain.id(), domain.hostname()));
                if (!alreadyMissing.contains(domain.id())) {
                    newlyMissing.add(domain.hostname());
                }
            }
        }
        log.debug("Custom domain reconcile compared hostnames={} ours={} ignored={}", hostnames.size(), ours.size(), ignored);
        return new Comparison(oursInCloudflare, List.copyOf(unknown), List.copyOf(missing), List.copyOf(newlyMissing));
    }

    private boolean confirmedMissing(final DomainInCloudflare domain) {
        final var result = cloudflareClient.get(domain.cloudflareId());
        if (result instanceof CloudflareResult.Ok<CloudflareHostname>) {
            log.info("Custom domain hostname missing from the listing but found in Cloudflare domainId={}", domain.id());
            return false;
        }
        if (result instanceof CloudflareResult.NotFound<CloudflareHostname> && MARKED_WHEN_MISSING.contains(domain.status())
                && domain.problem() != DomainProblem.PROVISIONING_FAILED) {
            customDomainLifecycle.recordMissing(domain.id(), domain.status());
        }
        return true;
    }

    private ReconcileRun recorded(final ReconcileRun run, final DomainDrift drift) {
        final var deleted = transactionOperations.execute(status -> {
            customDomainReconcileRepository.insert(run);
            if (drift != null) {
                eventPublisher.publishEvent(drift);
            }
            return customDomainReconcileRepository.deleteRanBefore(run.ranAt().minus(RUNS_KEPT));
        });
        log.info("Custom domain reconcile cloudflareTotal={} oursInCloudflare={} unknown={} missing={} failed={} oldRunsDeleted={}",
                run.cloudflareTotal(), run.oursInCloudflare(), run.unknownHostnames().size(), run.missingDomains().size(), run.error() != null,
                deleted);
        return run;
    }

    private ReconcileRun failed(final Integer cloudflareTotal, final String error) {
        return new ReconcileRun(now(), cloudflareTotal, null, List.of(), List.of(), CloudflareStatusMapping.cut(error));
    }

    private static String key(final String hostname) {
        return CustomDomainHostnames.hostnameKey(hostname).orElse("");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record QuotaReading(int used, int allocated, LocalDateTime readAt) {
    }

    private record Listing(List<CloudflareHostname> hostnames, Integer total, String error) {

        private static Listing failed(final String error) {
            return new Listing(List.of(), null, error);
        }

    }

    private record Comparison(int oursInCloudflare, List<String> unknown, List<MissingDomain> missing, List<String> newlyMissing) {
    }

}
