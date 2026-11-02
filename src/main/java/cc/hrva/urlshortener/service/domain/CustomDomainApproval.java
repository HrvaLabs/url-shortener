package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.service.LinkCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainApproval {

    private static final String BLOCKED_BY_RULE = "This domain matches a block rule.";
    private static final String HTTPS = "https://";

    private final LinkCheckService linkCheckService;
    private final CustomDomainProvisioner customDomainProvisioner;

    public boolean blockedByRule(final String hostname) {
        return linkCheckService.isBlockedByRule(HTTPS + hostname + "/");
    }

    public void requireNoBlockRule(final CustomDomain domain) {
        if (blockedByRule(domain.getHostname())) {
            log.info("Custom domain approval refused, the domain matches a block rule domainId={}", domain.getId());
            throw new CustomDomainConflictException(BLOCKED_BY_RULE);
        }
    }

    public void handOver(final long id) {
        try {
            customDomainProvisioner.provisionAsync(id);
        } catch (final RuntimeException exception) {
            log.warn("Custom domain provisioning couldn't start after its approval domainId={} exception={}", id, exception.getClass().getName());
        }
    }

}
