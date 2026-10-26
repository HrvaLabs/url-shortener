package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import java.time.LocalDateTime;

public final class BioLinkStates {

    private BioLinkStates() {}

    public static BioLinkState of(final Url url, final boolean inReview, final LocalDateTime now) {
        if (url.getBlockReason() != null || url.getThreatType() != null) {
            return BioLinkState.BLOCKED;
        }
        final var atLimit = hasReachedVisitLimit(url.getVisits(), url.getVisitLimit());
        final var expired = url.getExpirationDate() != null && url.getExpirationDate().isBefore(now);
        if (!url.isActive()) {
            if (atLimit) {
                return BioLinkState.LIMIT_REACHED;
            }
            return expired ? BioLinkState.EXPIRED : BioLinkState.DEACTIVATED;
        }
        if (expired) {
            return BioLinkState.EXPIRED;
        }
        if (atLimit) {
            return BioLinkState.LIMIT_REACHED;
        }
        return inReview ? BioLinkState.IN_REVIEW : BioLinkState.LIVE;
    }

    private static boolean hasReachedVisitLimit(final Long visits, final Long visitLimit) {
        return visitLimit != null && visitLimit > 0 && visits != null && visits >= visitLimit;
    }

}
