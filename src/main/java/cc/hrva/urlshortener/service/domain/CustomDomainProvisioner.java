package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainProvisioner {

    private final CustomDomainRepository customDomainRepository;
    private final CloudflareClient cloudflareClient;

    @Async(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR)
    public void provisionAsync(final long id) {
        provision(id);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void provision(final long id) {
        final var domain = customDomainRepository.findById(id).orElse(null);
        if (!approved(domain)) {
            log.info("Custom domain not provisioned, it isn't approved domainId={} status={}", id, domain == null ? null : domain.getStatus());
            return;
        }
        log.info("Custom domain approved and waiting to be provisioned domainId={}", id);
    }

    private static boolean approved(final CustomDomain domain) {
        return domain != null && domain.getStatus() == CustomDomainStatus.APPROVED_PROVISIONING && domain.getReviewedAt() != null;
    }

}
