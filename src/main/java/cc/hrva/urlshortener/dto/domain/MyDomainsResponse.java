package cc.hrva.urlshortener.dto.domain;

import java.util.List;

public record MyDomainsResponse(List<CustomDomainResponse> domains, int limit, long used, boolean canApply) {

    public MyDomainsResponse {
        domains = List.copyOf(domains);
    }

}
