package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomDomainLimits {

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainAllowanceRepository customDomainAllowanceRepository;
    private final AppProperties appProperties;

    public int limitFor(final long userId) {
        return customDomainAllowanceRepository.findById(userId)
                .map(CustomDomainAllowance::getMaxDomains)
                .orElseGet(() -> appProperties.getDomains().getPerUser());
    }

    public long usedBy(final long userId) {
        return customDomainRepository.countByOwnerIdAndStatusIn(userId, CustomDomainStatus.COUNTS_FOR_USER);
    }

}
