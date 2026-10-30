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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainProvisioner {

    private static final Duration FIRST_CHECK = Duration.ofMinutes(1);
    private static final int DUPLICATE_CODE = 1406;

    private final CustomDomainRepository customDomainRepository;
    private final CloudflareClient cloudflareClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    public void provisionAsync(final long id) {
        provision(id);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LifecycleOutcome provision(final long id) {
        if (!inFlight.add(id)) {
            log.info("Custom domain provisioning skipped, it is already running domainId={}", id);
            return LifecycleOutcome.SKIPPED;
        }
        try {
            return provisionOnce(id);
        } finally {
            inFlight.remove(id);
        }
    }

    private LifecycleOutcome provisionOnce(final long id) {
        final var domain = fresh(id);
        if (!approved(domain)) {
            log.info("Custom domain not provisioned, it isn't approved domainId={} status={}", id, domain == null ? null : domain.getStatus());
            return LifecycleOutcome.SKIPPED;
        }
        final var attempt = attempt(domain.getHostname());
        return attempt.cloudflareId() == null ? recordFailure(domain, attempt.error()) : recordCreated(domain, attempt);
    }

    private Attempt attempt(final String hostname) {
        final var lookup = cloudflareClient.findByHostname(hostname);
        if (!(lookup instanceof CloudflareResult.Ok<Optional<CloudflareHostname>>(final var found, final var ignored))) {
            return Attempt.failed(CloudflareStatusMapping.failureText(lookup));
        }
        return found.map(existing -> Attempt.adopted(existing.id())).orElseGet(() -> create(hostname));
    }

    private Attempt create(final String hostname) {
        final var created = cloudflareClient.create(hostname);
        if (created instanceof CloudflareResult.Ok<CloudflareHostname>(final var hostnameCreated, final var ignored)) {
            return Attempt.created(hostnameCreated.id());
        }
        if (created instanceof CloudflareResult.Refused<CloudflareHostname> refused && duplicate(refused)) {
            log.info("Cloudflare already has the custom hostname, looking it up once more");
            return lookUpOnceMore(hostname, refused);
        }
        return Attempt.failed(CloudflareStatusMapping.failureText(created));
    }

    private Attempt lookUpOnceMore(final String hostname, final CloudflareResult.Refused<CloudflareHostname> refused) {
        final var lookup = cloudflareClient.findByHostname(hostname);
        if (lookup instanceof CloudflareResult.Ok<Optional<CloudflareHostname>>(final var found, final var ignored)) {
            return found.map(existing -> Attempt.adopted(existing.id()))
                    .orElseGet(() -> Attempt.failed(CloudflareStatusMapping.failureText(refused)));
        }
        return Attempt.failed(CloudflareStatusMapping.failureText(lookup));
    }

    private LifecycleOutcome recordCreated(final CustomDomain domain, final Attempt attempt) {
        final var now = LocalDateTime.now(clock);
        final var written = transactionOperations.execute(status -> {
            final var updated = customDomainRepository.recordCreated(domain.getId(), attempt.cloudflareId(), now, now.plus(FIRST_CHECK));
            if (updated == 1) {
                eventPublisher.publishEvent(new CustomDomainChangedEvent(domain.getId()));
            }
            return updated;
        });
        if (Objects.equals(written, 1)) {
            log.info("Custom domain set up in Cloudflare domainId={} cloudflareId={} created={}", domain.getId(), attempt.cloudflareId(),
                    attempt.created());
            return LifecycleOutcome.DONE;
        }
        return lostTheDomain(domain.getId(), attempt);
    }

    private LifecycleOutcome lostTheDomain(final long id, final Attempt attempt) {
        final var current = fresh(id);
        if (current != null && attempt.cloudflareId().equals(current.getCloudflareId())) {
            log.info("Custom domain was already recorded with its Cloudflare hostname domainId={}", id);
            return LifecycleOutcome.SKIPPED;
        }
        if (attempt.created() && (current == null || current.getStatus() == CustomDomainStatus.DELETED)) {
            final var deleted = cloudflareClient.delete(attempt.cloudflareId());
            log.info("Custom domain was removed while its hostname was created, so the hostname was deleted again domainId={} cloudflareId={} "
                    + "deleted={}", id, attempt.cloudflareId(), deleted instanceof CloudflareResult.Ok<Void>);
            return LifecycleOutcome.SKIPPED;
        }
        log.warn("Custom domain changed while it was set up in Cloudflare, the reconcile will list the hostname domainId={} cloudflareId={} "
                + "status={}", id, attempt.cloudflareId(), current == null ? null : current.getStatus());
        return LifecycleOutcome.SKIPPED;
    }

    private LifecycleOutcome recordFailure(final CustomDomain domain, final String error) {
        final var now = LocalDateTime.now(clock);
        final var nextTry = DomainCheckSchedule.nextCheck(CustomDomainStatus.APPROVED_PROVISIONING, DomainProblem.PROVISIONING_FAILED,
                domain.getReviewedAt(), domain.getCheckCount() + 1, now);
        transactionOperations.executeWithoutResult(status -> {
            if (customDomainRepository.recordProvisioningFailure(domain.getId(), error, nextTry, now) == 1 && nextTry == null) {
                eventPublisher.publishEvent(new CustomDomainChangedEvent(domain.getId()));
                eventPublisher.publishEvent(new DomainNeedsAttention(domain.getId(), domain.getHostname(), DomainProblem.PROVISIONING_FAILED));
            }
        });
        if (nextTry == null) {
            log.warn("Custom domain setup in Cloudflare failed and waits for a retry domainId={} hostname={}", domain.getId(), domain.getHostname());
        } else {
            log.info("Custom domain setup in Cloudflare failed, trying again later domainId={} nextTryAt={}", domain.getId(), nextTry);
        }
        return LifecycleOutcome.FAILED;
    }

    private CustomDomain fresh(final long id) {
        return transactionOperations.execute(status -> customDomainRepository.findById(id).orElse(null));
    }

    private static boolean duplicate(final CloudflareResult.Refused<CloudflareHostname> refused) {
        return refused.httpStatus() == HttpStatus.CONFLICT.value() || refused.errorCode() == DUPLICATE_CODE;
    }

    private static boolean approved(final CustomDomain domain) {
        return domain != null && domain.getStatus() == CustomDomainStatus.APPROVED_PROVISIONING && domain.getReviewedAt() != null;
    }

    private record Attempt(String cloudflareId, boolean created, String error) {

        private static Attempt adopted(final String cloudflareId) {
            return new Attempt(cloudflareId, false, null);
        }

        private static Attempt created(final String cloudflareId) {
            return new Attempt(cloudflareId, true, null);
        }

        private static Attempt failed(final String error) {
            return new Attempt(null, false, error);
        }

    }

}
