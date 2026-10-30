package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.CustomDomainCapacityException;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainSetup {

    private static final String CAP_REACHED = "Cap reached: %d of %d custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).";
    private static final String FREE_ALLOWANCE_USED = "Cloudflare already has %d custom hostnames and %d are free. Delete one here or in Cloudflare first.";
    private static final String NOT_SET_UP = "Cloudflare isn't set up: %s. Check CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID.";
    private static final String NOTHING_TO_RETRY = "There's nothing to retry for this domain.";
    private static final Map<CloudflareTokenStatus, String> TOKEN_PROBLEMS = Map.of(
            CloudflareTokenStatus.MISSING, "the API token is missing",
            CloudflareTokenStatus.INVALID, "the API token is invalid",
            CloudflareTokenStatus.NO_PERMISSION, "the API token can't manage custom hostnames",
            CloudflareTokenStatus.UNREACHABLE, "Cloudflare can't be reached");

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainCapLock customDomainCapLock;
    private final CloudflareTokenCheck cloudflareTokenCheck;
    private final CustomDomainProvisioner customDomainProvisioner;
    private final CustomDomainLifecycle customDomainLifecycle;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final AppProperties appProperties;
    private final Clock clock;

    public int liveTotal(final long id) {
        final var token = cloudflareTokenCheck.check();
        if (token.status() != CloudflareTokenStatus.OK) {
            log.info("Custom domain setup refused, the Cloudflare token isn't usable domainId={} status={}", id, token.status());
            throw new CustomDomainCapacityException(NOT_SET_UP.formatted(TOKEN_PROBLEMS.get(token.status())));
        }
        if (token.cloudflareTotal() == null) {
            log.info("Custom domain setup refused, Cloudflare gave no total domainId={}", id);
            throw new CustomDomainCapacityException(NOT_SET_UP.formatted(TOKEN_PROBLEMS.get(CloudflareTokenStatus.UNREACHABLE)));
        }
        return token.cloudflareTotal();
    }

    public void requireRoom(final long id, final int cloudflareTotal, final boolean holdsASlot) {
        final var settings = appProperties.getDomains();
        final var slotsInUse = customDomainRepository.countSlotsInUse() - (holdsASlot ? 1 : 0);
        if (slotsInUse >= settings.getMaxActive()) {
            log.info("Custom domain refused at the cap domainId={} slotsInUse={} maxActive={}", id, slotsInUse, settings.getMaxActive());
            throw new CustomDomainCapacityException(CAP_REACHED.formatted(slotsInUse, settings.getMaxActive()));
        }
        final var notYetInCloudflare = customDomainRepository.countByStatus(CustomDomainStatus.APPROVED_PROVISIONING);
        final var hostnames = cloudflareTotal + notYetInCloudflare;
        if (hostnames >= settings.getFreeAllowance()) {
            log.info("Custom domain refused at the free allowance domainId={} cloudflareTotal={} notYetInCloudflare={} freeAllowance={}", id,
                    cloudflareTotal, notYetInCloudflare, settings.getFreeAllowance());
            throw new CustomDomainCapacityException(FREE_ALLOWANCE_USED.formatted(hostnames, settings.getFreeAllowance()));
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retry(final CustomDomain domain) {
        final var id = domain.getId();
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
    }

    private void provisionAgain(final CustomDomain domain) {
        final var id = domain.getId();
        final var cloudflareTotal = liveTotal(id);
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

}
