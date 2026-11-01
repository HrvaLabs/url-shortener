package cc.hrva.urlshortener.model;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSessionsTest {

    private static final Instant CUT_OFF = Instant.parse("2026-10-30T19:02:13.750Z");

    @Test
    void anAccountWhoseSessionsNeverEndedAcceptsEveryToken() {
        final var user = new User();

        assertThat(user.sessionEndedFor(Instant.parse("2020-01-01T00:00:00Z"))).isFalse();
        assertThat(user.sessionEndedFor(null)).isFalse();
    }

    @Test
    void theCutOffIsKeptInUtcToTheSecond() {
        final var user = new User();

        user.endSessions(CUT_OFF);

        assertThat(user.getTokensValidAfter()).isEqualTo(LocalDateTime.parse("2026-10-30T19:02:13"));
    }

    @Test
    void aTokenIssuedInTheSecondOfTheCutOffStaysValid() {
        final var user = new User();

        user.endSessions(CUT_OFF);

        assertThat(user.sessionEndedFor(Instant.parse("2026-10-30T19:02:13Z"))).isFalse();
        assertThat(user.sessionEndedFor(Instant.parse("2026-10-30T19:02:14Z"))).isFalse();
    }

    @Test
    void aTokenIssuedASecondBeforeTheCutOffHasEnded() {
        final var user = new User();

        user.endSessions(CUT_OFF);

        assertThat(user.sessionEndedFor(Instant.parse("2026-10-30T19:02:12Z"))).isTrue();
    }

    @Test
    void aTokenWithoutAnIssueTimeHasEndedOnceSessionsWereEnded() {
        final var user = new User();

        user.endSessions(CUT_OFF);

        assertThat(user.sessionEndedFor(null)).isTrue();
    }

}
