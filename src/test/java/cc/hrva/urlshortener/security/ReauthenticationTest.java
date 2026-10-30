package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import cc.hrva.urlshortener.validator.AuthValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReauthenticationTest {

    private static final Instant NOW = Instant.parse("2026-10-25T14:00:00Z");
    private static final String TOKEN = "header.payload.signature";
    private static final String SIGN_IN_AGAIN = "For your security, sign in again to change your passkeys and security keys.";
    private static final String SIGN_IN_AGAIN_TO_DELETE = "For your security, sign in again to delete your account.";
    private static final String WRONG_PASSWORD = "Current password is incorrect";
    private static final String TOO_MANY_WRONG_PASSWORDS = "Too many wrong passwords. Try again in 24 hours.";

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private AuthValidator authValidator;

    private DefaultLoginAttemptService loginAttemptService;

    private Reauthentication reauthentication;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setMaxLoginAttempts(20L);
        loginAttemptService = new DefaultLoginAttemptService(appProperties);
        reauthentication = new Reauthentication(new RecentSignIn(tokenProvider, Clock.fixed(NOW, ZoneOffset.UTC)), authValidator,
                loginAttemptService);
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
    void theTwentyFirstWrongPasswordIsRefusedBeforeTheCompare() {
        final var user = user("local");
        doThrow(new NoAuthorizationException(WRONG_PASSWORD)).when(authValidator).passwordMatchesCurrentPassword(user, "wrong");

        IntStream.rangeClosed(1, 20).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirm(user, "wrong"))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(WRONG_PASSWORD));

        for (final var password : new String[] {"wrong", "user123"}) {
            assertThatThrownBy(() -> reauthentication.confirm(user, password))
                    .isInstanceOf(NoAuthorizationException.class)
                    .hasMessage(TOO_MANY_WRONG_PASSWORDS);
        }
        verify(authValidator, times(20)).passwordMatchesCurrentPassword(user, "wrong");
        verify(authValidator, never()).passwordMatchesCurrentPassword(user, "user123");
        assertThat(attemptsByKey()).containsEntry("reauth:7", 20);
    }

    @Test
    void theRightPasswordResetsTheCounter() {
        final var user = user("local");
        doThrow(new NoAuthorizationException(WRONG_PASSWORD)).when(authValidator).passwordMatchesCurrentPassword(user, "wrong");
        IntStream.rangeClosed(1, 19).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirm(user, "wrong"))
                .hasMessage(WRONG_PASSWORD));

        reauthentication.confirm(user, "user123");

        assertThat(attemptsByKey()).doesNotContainKey("reauth:7");
        IntStream.rangeClosed(1, 19).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirm(user, "wrong"))
                .hasMessage(WRONG_PASSWORD));
        assertThatCode(() -> reauthentication.confirm(user, "user123")).doesNotThrowAnyException();
        verify(authValidator, times(2)).passwordMatchesCurrentPassword(user, "user123");
    }

    @Test
    void theCounterIsPerAccount() {
        final var user = user("local");
        final var otherUser = User.builder().id(8L).email("other@example.com").password("$2a$10$hash").authProvider("local").build();
        doThrow(new NoAuthorizationException(WRONG_PASSWORD)).when(authValidator).passwordMatchesCurrentPassword(otherUser, "wrong");
        IntStream.rangeClosed(1, 20).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirm(otherUser, "wrong"))
                .hasMessage(WRONG_PASSWORD));

        assertThatCode(() -> reauthentication.confirm(user, "user123")).doesNotThrowAnyException();
        assertThatThrownBy(() -> reauthentication.confirm(otherUser, "wrong")).hasMessage(TOO_MANY_WRONG_PASSWORDS);
    }

    @Test
    void aBlankPasswordIsNotCounted() {
        final var user = user("local");

        IntStream.rangeClosed(1, 25).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirm(user, " "))
                .hasMessage("Enter your current password."));

        assertThat(attemptsByKey()).doesNotContainKey("reauth:7");
    }

    @Test
    void confirmingThePasswordAloneUsesTheSameCounterForAnyAccount() {
        final var user = user("google");
        doThrow(new NoAuthorizationException(WRONG_PASSWORD)).when(authValidator).passwordMatchesCurrentPassword(user, "wrong");
        IntStream.rangeClosed(1, 20).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirmPassword(user, "wrong"))
                .hasMessage(WRONG_PASSWORD));

        assertThatThrownBy(() -> reauthentication.confirmPassword(user, "wrong")).hasMessage(TOO_MANY_WRONG_PASSWORDS);
        verify(authValidator, times(20)).passwordMatchesCurrentPassword(user, "wrong");
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
        assertThat(attemptsByKey()).isEmpty();
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
    void deletingAnOAuthAccountWithAStaleTokenAsksToSignInAgainToDeleteIt() {
        signedInMinutesAgo(16);

        assertThatThrownBy(() -> reauthentication.confirmAccountDeletion(user("google"), null))
                .isInstanceOf(ReauthenticationRequiredException.class)
                .hasMessage(SIGN_IN_AGAIN_TO_DELETE);
        verifyNoInteractions(authValidator);
    }

    @Test
    void deletingAnOAuthAccountWithAFreshTokenPasses() {
        signedInMinutesAgo(14);

        assertThatCode(() -> reauthentication.confirmAccountDeletion(user("github"), null)).doesNotThrowAnyException();
    }

    @Test
    void deletingALocalAccountChecksThePasswordOnTheSameCounter() {
        final var user = user("local");
        doThrow(new NoAuthorizationException(WRONG_PASSWORD)).when(authValidator).passwordMatchesCurrentPassword(user, "wrong");
        IntStream.rangeClosed(1, 20).forEach(attempt -> assertThatThrownBy(() -> reauthentication.confirmAccountDeletion(user, "wrong"))
                .hasMessage(WRONG_PASSWORD));

        assertThatThrownBy(() -> reauthentication.confirm(user, "user123")).hasMessage(TOO_MANY_WRONG_PASSWORDS);
    }

    @Test
    void forgettingWrongPasswordsUnblocksTheAccount() {
        final var user = user("local");
        IntStream.rangeClosed(1, 20).forEach(attempt -> loginAttemptService.loginFailed("reauth:7"));

        reauthentication.forgetWrongPasswords(user);

        assertThatCode(() -> reauthentication.confirm(user, "user123")).doesNotThrowAnyException();
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

    private Map<String, Integer> attemptsByKey() {
        return loginAttemptService.attempts(null, Pageable.unpaged()).stream()
                .collect(Collectors.toMap(LoginAttemptResponse::key, LoginAttemptResponse::attempts));
    }

}
