package cc.hrva.urlshortener.model.enums;

import java.util.EnumSet;
import java.util.Set;

public enum DomainProblem {

    DNS_NOT_FOUND,
    CAA_BLOCKED,
    CERTIFICATE_TIMED_OUT,
    CNAME_REMOVED,
    CLOUDFLARE_BLOCKED,
    PROVISIONING_FAILED,
    DNS_TIMED_OUT;

    private static final Set<DomainProblem> RECHECKABLE = EnumSet.of(CERTIFICATE_TIMED_OUT, CNAME_REMOVED, CAA_BLOCKED, CLOUDFLARE_BLOCKED);

    public boolean recheckable() {
        return RECHECKABLE.contains(this);
    }

}
