package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ThreatType;
import java.time.LocalDateTime;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class BioLinkStatesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 9, 0);

    @ParameterizedTest(name = "{0}")
    @MethodSource("links")
    void eachLinkGetsTheFirstMatchingState(final UnaryOperator<Url.UrlBuilder> link, final boolean inReview, final BioLinkState expected) {
        final var url = link.apply(Url.builder().id(41L).shortUrl("ana-ig").longUrl("https://instagram.com/ana").active(true).visits(3L)).build();

        assertThat(BioLinkStates.of(url, inReview, NOW)).isEqualTo(expected);
    }

    private static Stream<Arguments> links() {
        return Stream.of(
                arguments(named("liveIsLive", (UnaryOperator<Url.UrlBuilder>) link -> link), false, BioLinkState.LIVE),
                arguments(named("blockedWinsOverEverything", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .blockReason(BlockReason.BLOCKLIST).active(false).visitLimit(3L).expirationDate(NOW.minusDays(1))), true, BioLinkState.BLOCKED),
                arguments(named("aThreatIsBlocked", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .threatType(ThreatType.SOCIAL_ENGINEERING)), false, BioLinkState.BLOCKED),
                arguments(named("deactivatedAtItsLimitReachedTheLimit", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .active(false).visitLimit(3L).expirationDate(NOW.minusDays(1))), false, BioLinkState.LIMIT_REACHED),
                arguments(named("deactivatedPastItsExpiryExpired", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .active(false).visitLimit(10L).expirationDate(NOW.minusDays(1))), false, BioLinkState.EXPIRED),
                arguments(named("deactivatedByTheOwnerIsDeactivated", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .active(false).expirationDate(NOW.plusDays(1))), true, BioLinkState.DEACTIVATED),
                arguments(named("activeButPastExpiryIsExpired", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .expirationDate(NOW.minusSeconds(1)).visitLimit(3L)), true, BioLinkState.EXPIRED),
                arguments(named("activeUntilTheMomentOfItsExpiry", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .expirationDate(NOW)), false, BioLinkState.LIVE),
                arguments(named("activeButAtItsLimitReachedTheLimit", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .visitLimit(3L)), true, BioLinkState.LIMIT_REACHED),
                arguments(named("activeBelowItsLimitIsLive", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .visitLimit(4L)), false, BioLinkState.LIVE),
                arguments(named("aZeroLimitIsNoLimit", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .visitLimit(0L)), false, BioLinkState.LIVE),
                arguments(named("noVisitsYetIsBelowTheLimit", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .visits(null).visitLimit(1L)), false, BioLinkState.LIVE),
                arguments(named("anOpenSystemReviewIsInReview", (UnaryOperator<Url.UrlBuilder>) link -> link
                        .expirationDate(NOW.plusDays(1)).visitLimit(4L)), true, BioLinkState.IN_REVIEW));
    }

}
