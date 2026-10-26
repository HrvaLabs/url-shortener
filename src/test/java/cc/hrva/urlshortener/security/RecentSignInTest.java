package cc.hrva.urlshortener.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentSignInTest {

    private static final Instant NOW = Instant.parse("2026-10-25T14:00:00Z");
    private static final String TOKEN = "header.payload.signature";

    @Mock
    private TokenProvider tokenProvider;

    private RecentSignIn recentSignIn;

    @BeforeEach
    void setUp() {
        this.recentSignIn = new RecentSignIn(tokenProvider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aSignInFourteenMinutesAgoIsRecent() {
        when(tokenProvider.issuedAt(TOKEN)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(14))));

        assertThat(recentSignIn.isRecent(signedInWith(TOKEN))).isTrue();
    }

    @Test
    void aSignInSixteenMinutesAgoIsNotRecent() {
        when(tokenProvider.issuedAt(TOKEN)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(16))));

        assertThat(recentSignIn.isRecent(signedInWith(TOKEN))).isFalse();
    }

    @Test
    void aTokenWithoutIssueTimeIsNotRecent() {
        when(tokenProvider.issuedAt(TOKEN)).thenReturn(Optional.empty());

        assertThat(recentSignIn.isRecent(signedInWith(TOKEN))).isFalse();
    }

    @Test
    void noCredentialsAreNotRecent() {
        assertThat(recentSignIn.isRecent(signedInWith(null))).isFalse();
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void aCredentialThatIsNoTokenIsNotRecent() {
        assertThat(recentSignIn.isRecent(signedInWith(new char[] {'x'}))).isFalse();
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void noAuthenticationIsNotRecent() {
        assertThat(recentSignIn.isRecent(null)).isFalse();
        verifyNoInteractions(tokenProvider);
    }

    private static Authentication signedInWith(final Object credentials) {
        final var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));

        return new UsernamePasswordAuthenticationToken("user@example.com", credentials, authorities);
    }

}
