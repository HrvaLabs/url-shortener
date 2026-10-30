package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;

public record DomainRouteRow(Long id, String hostname, String hostnameKey, CustomDomainStatus status, Long ownerId, Boolean ownerActive) {
}
