package cc.hrva.urlshortener.model.enums;

import java.util.Set;

public enum BlockReason {

    SAFE_BROWSING,
    BLOCKLIST,
    DOMAIN_RULE,
    ADMIN,
    USER_BLOCKED,
    SCAM_SIGNALS,
    CAMPAIGN;

    public static final Set<BlockReason> MANUAL = Set.of(ADMIN, USER_BLOCKED);

}
