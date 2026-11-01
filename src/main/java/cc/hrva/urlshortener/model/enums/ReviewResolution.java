package cc.hrva.urlshortener.model.enums;

import java.util.Optional;

public enum ReviewResolution {

    OK,
    DOMAIN_TRUSTED,
    LINK_BLOCKED,
    DOMAIN_BLOCKED,
    USER_BLOCKED,
    DESTINATION_CHANGED;

    public static Optional<ReviewResolution> ofBlock(final BlockReason reason) {
        return switch (reason) {
            case CAMPAIGN, SIGN_IN_FORM -> Optional.empty();
            case DOMAIN_RULE -> Optional.of(DOMAIN_BLOCKED);
            case USER_BLOCKED -> Optional.of(USER_BLOCKED);
            case SAFE_BROWSING, BLOCKLIST, ADMIN, SCAM_SIGNALS -> Optional.of(LINK_BLOCKED);
        };
    }

}
