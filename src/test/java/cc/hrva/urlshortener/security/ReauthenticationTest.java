package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.validator.AuthValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReauthenticationTest {

    private static final Instant NOW = Instant.parse("2026-10-25T14:00:00Z");
    private static final String TOKEN = "header.payload.signature";
    private static final String SIGN_IN_AGAIN = "For your security, sign in again to change your passkeys and security keys.";

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private AuthValidator authValidator;

    private Reauthentication reauthentication;

    @BeforeEach
    void setUp() {
        reauthentication = new Reauthentication(new RecentSignIn(tokenProvider, Clock.fixed(NOW, ZoneOffset.UTC)), authValidator);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aLocalAccountNeedsItsPassword() {
        signedInMinutesAgo(1);

        for (final var user : List.of(user("local"), user(null))) {
            for (final var password : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> reauthentication.confirm(user, password))
                        .isInstanceOf(ApiException.class)
                        .hasMessage("Enter your current password.");
            }
        }
        verifyNoInteractions(authValidator);
    }

    @Test
    void aWrongPasswordIs403() {
        final var user = user("local");
        doThrow(new NoAuthorizationException("Current password is incorrect"))
                .when(authValidator).passwordMatchesCurrentPassword(user, "wrong");

        assertThatThrownBy(() -> reauthentication.confirm(user, "wrong"))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Current password is incorrect");
    }

    @Test
    void theRightPasswordPasses() {
        final var user = user("local");

        assertThatCode(() -> reauthentication.confirm(user, "user123")).doesNotThrowAnyException();

        verify(authValidator).passwordMatchesCurrentPassword(user, "user123");
    }

    @Test
    void anOAuthAccountWithAFreshTokenPasses() {
        signedInMinutesAgo(14);

        assertThatCode(() -> reauthentication.confirm(user("google"), null)).doesNotThrowAnyException();

        verifyNoInteractions(authValidator);
    }

    @Test
    void anOAuthAccountWithAStaleTokenIs401() {
        signedInMinutesAgo(16);

        for (final var password : new String[] {null, "any password"}) {
            assertThatThrownBy(() -> reauthentication.confirm(user("github"), password))
                    .isInstanceOf(ReauthenticationRequiredException.class)
                    .hasMessage(SIGN_IN_AGAIN);
        }
        verifyNoInteractions(authValidator);
    }

    @Test
    void aTokenFromBeforeIatIsStale() {
        signedIn();
        when(tokenProvider.issuedAt(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reauthentication.confirm(user("google"), null))
                .isInstanceOf(ReauthenticationRequiredException.class)
                .hasMessage(SIGN_IN_AGAIN);
    }

    @Test
    void anOAuthAccountWithoutASignInIs401() {
        assertThatThrownBy(() -> reauthentication.confirm(user("google"), null))
                .isInstanceOf(ReauthenticationRequiredException.class)
                .hasMessage(SIGN_IN_AGAIN);
    }

    private void signedInMinutesAgo(final long minutes) {
        signedIn();
        lenient().when(tokenProvider.issuedAt(TOKEN)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(minutes))));
    }

    private static void signedIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@example.com", TOKEN, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static User user(final String authProvider) {
        return User.builder().id(7L).email("user@example.com").password("$2a$10$hash").authProvider(authProvider).build();
    }

}
