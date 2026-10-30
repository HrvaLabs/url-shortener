package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.model.CustomDomainAllowance;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public record DomainAllowanceResponse(long userId, int maxDomains, String reason, String grantedBy, LocalDateTime grantedAt) {

    public static DomainAllowanceResponse of(final CustomDomainAllowance allowance) {
        return new DomainAllowanceResponse(allowance.getUserId(), allowance.getMaxDomains(), allowance.getReason(), allowance.getGrantedBy(),
                allowance.getGrantedAt() == null ? null : allowance.getGrantedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    public static DomainAllowanceResponse byDefault(final long userId, final int maxDomains) {
        return new DomainAllowanceResponse(userId, maxDomains, null, null, null);
    }

}
