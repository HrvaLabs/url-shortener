package cc.hrva.urlshortener.model.enums;

import java.util.Set;

public enum CustomDomainStatus {

    PENDING_REVIEW,
    APPROVED_PROVISIONING,
    AWAITING_DNS,
    ACTIVE,
    REJECTED,
    DISABLED,
    ERROR,
    DELETED;

    public static final Set<CustomDomainStatus> HOLDS_HOSTNAME = Set.of(PENDING_REVIEW, APPROVED_PROVISIONING, AWAITING_DNS, ACTIVE, DISABLED, ERROR);
    public static final Set<CustomDomainStatus> COUNTS_FOR_USER = Set.of(PENDING_REVIEW, APPROVED_PROVISIONING, AWAITING_DNS, ACTIVE, DISABLED, ERROR);

}
