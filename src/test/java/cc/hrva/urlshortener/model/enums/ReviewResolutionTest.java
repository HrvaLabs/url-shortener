package cc.hrva.urlshortener.model.enums;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewResolutionTest {

    @Test
    void aBlockClosesTheReviewWithTheResolutionOfItsReasonAndAHoldLeavesItOpen() {
        final var resolutions = new EnumMap<BlockReason, Optional<ReviewResolution>>(BlockReason.class);
        Arrays.stream(BlockReason.values()).forEach(reason -> resolutions.put(reason, ReviewResolution.ofBlock(reason)));

        assertThat(resolutions).containsExactlyInAnyOrderEntriesOf(Map.of(
                BlockReason.SAFE_BROWSING, Optional.of(ReviewResolution.LINK_BLOCKED),
                BlockReason.BLOCKLIST, Optional.of(ReviewResolution.LINK_BLOCKED),
                BlockReason.DOMAIN_RULE, Optional.of(ReviewResolution.DOMAIN_BLOCKED),
                BlockReason.ADMIN, Optional.of(ReviewResolution.LINK_BLOCKED),
                BlockReason.USER_BLOCKED, Optional.of(ReviewResolution.USER_BLOCKED),
                BlockReason.SCAM_SIGNALS, Optional.of(ReviewResolution.LINK_BLOCKED),
                BlockReason.CAMPAIGN, Optional.empty(),
                BlockReason.SIGN_IN_FORM, Optional.empty()));
    }

}
