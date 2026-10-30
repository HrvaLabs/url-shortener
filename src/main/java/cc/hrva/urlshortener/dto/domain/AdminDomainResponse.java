package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.LocalDateTime;
import java.util.List;

public record AdminDomainResponse(
        @JsonUnwrapped CustomDomainResponse domain,
        DomainOwnerSummary owner,
        List<String> applyFlags,
        String cloudflareId,
        String cloudflareStatus,
        String sslStatus,
        String lastError,
        DomainDisableReason disabledReason,
        LocalDateTime disabledAt,
        LocalDateTime deletedAt,
        long clicks) {

    public AdminDomainResponse {
        applyFlags = applyFlags == null ? List.of() : List.copyOf(applyFlags);
    }

}
