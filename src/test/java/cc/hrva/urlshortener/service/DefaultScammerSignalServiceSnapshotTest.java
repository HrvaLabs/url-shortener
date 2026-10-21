package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultScammerSignalService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultScammerSignalServiceSnapshotTest {

    @Mock
    private UrlRepository urlRepository;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-21T09:00:00Z"));

    private ScammerSignalService scammerSignalService;

    @BeforeEach
    void setUp() {
        scammerSignalService = new DefaultScammerSignalService(urlRepository, clock);
        when(urlRepository.findOwnerIdsSignedUpAfterWithFlaggedOrBlockedLinks(any(), anyLong())).thenReturn(List.of(27L));
        when(urlRepository.findOwnerIdsWithBlockedLinks(anyLong())).thenReturn(List.of(41L));
        when(urlRepository.findOwnerIdsWithLinksSoonAfterSignup(anyLong(), anyLong())).thenReturn(List.of());
    }

    @Test
    void possibleScammersAreWorkedOutOnceAMinute() {
        assertThat(scammerSignalService.possibleScammerIds()).containsExactlyInAnyOrder(27L, 41L);
        clock.advance(Duration.ofSeconds(59));
        assertThat(scammerSignalService.possibleScammerIds()).containsExactlyInAnyOrder(27L, 41L);
        assertThat(scammerSignalService.possibleScammers(List.of(User.builder().id(27L).build()))).containsExactly(27L);

        verifyQueriedTimes(1);
    }

    @Test
    void possibleScammersAreWorkedOutAgainOnceTheMinuteIsUp() {
        scammerSignalService.possibleScammerIds();
        when(urlRepository.findOwnerIdsWithBlockedLinks(anyLong())).thenReturn(List.of(41L, 52L));
        clock.advance(Duration.ofSeconds(60));

        assertThat(scammerSignalService.possibleScammerIds()).containsExactlyInAnyOrder(27L, 41L, 52L);
        verifyQueriedTimes(2);
    }

    @Test
    void invalidatingWorksThePossibleScammersOutAgainOnTheNextAsk() {
        scammerSignalService.possibleScammerIds();
        when(urlRepository.findOwnerIdsWithBlockedLinks(anyLong())).thenReturn(List.of());

        scammerSignalService.invalidate();

        assertThat(scammerSignalService.possibleScammerIds()).containsExactly(27L);
        verifyQueriedTimes(2);
    }

    @Test
    void aClockThatJumpsBackDoesNotKeepAnOldAnswer() {
        scammerSignalService.possibleScammerIds();
        clock.advance(Duration.ofHours(-1));

        scammerSignalService.possibleScammerIds();

        verifyQueriedTimes(2);
    }

    private void verifyQueriedTimes(final int times) {
        verify(urlRepository, times(times)).findOwnerIdsSignedUpAfterWithFlaggedOrBlockedLinks(any(), anyLong());
        verify(urlRepository, times(times)).findOwnerIdsWithBlockedLinks(anyLong());
        verify(urlRepository, times(times)).findOwnerIdsWithLinksSoonAfterSignup(anyLong(), anyLong());
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(final Instant now) {
            this.now = now;
        }

        private void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

}
