package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;

public record DomainRoute(long id, String hostname, CustomDomainStatus status, boolean ownerActive) {

    public boolean serves() {
        return status == CustomDomainStatus.ACTIVE && ownerActive;
    }

}
