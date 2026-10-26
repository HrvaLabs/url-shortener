package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.impl.DefaultSecondStepService;
import cc.hrva.urlshortener.service.twostep.PendingLogin;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSecondStepServiceTest {

    private static final String CLIENT_IP = "203.0.113.9";
    private static final String TOKEN = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q";
    private static final String ATTEMPTS_KEY = "pending-login:" + TOKEN;
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final String INVALID_CODE = "Invalid two-factor code";
    private static final String SIGN_IN_EXPIRED = "Your sign-in expired. Sign in again.";

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private PendingLoginStore pendingLoginStore;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TwoFactorService twoFactorService;

    @Mock
    private SignInService signInService;

    @Mock
    private HttpServletRequest request;

    private SecondStepService secondStepService;

    @BeforeEach
    void setUp() {
        secondStepService = new DefaultSecondStepService(clientIpResolver, loginAttemptService, pendingLoginStore,
                rateLimiter, userRepository, twoFactorService, signInService);
        when(clientIpResolver.getClientIp(request)).thenReturn(CLIENT_IP);
    }

    @Test
    void aCorrectTotpCodeSignsInWithTheRememberedChoice() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, true), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, true)));
        when(signInService.signIn(user, true)).thenReturn(signedIn);

        final var response = secondStepService.completeWithCode(totp("123456"), request);

        assertThat(response).isSameAs(signedIn);
        verify(loginAttemptService).loginSucceeded("2fa:7");
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void aRecoveryCodeSignsIn() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, false), user);
        when(twoFactorService.verifyLoginCode(user, "ABCD-EF23")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(signInService.signIn(user, false)).thenReturn(signedIn);

        assertThat(secondStepService.completeWithCode(totp("ABCD-EF23"), request)).isSameAs(signedIn);
    }

    @Test
    void aWrongCodeCountsAgainstTheNetworkAndTheAccount() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false), user);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("000000"), request))
                .isInstanceOf(TwoStepFailedException.class)
                .hasMessage(INVALID_CODE);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).consume(anyString());
        verify(pendingLoginStore, never()).discard(anyString());
        verify(signInService, never()).signIn(any(), anyBoolean());
    }

    @Test
    void theSixthAttemptOnOneTokenIsRefusedAndTheTokenIsDiscarded() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(rateLimiter.tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES)).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(pendingLoginStore).discard(TOKEN);
        verifyNoInteractions(userRepository, twoFactorService, signInService);
    }

    @Test
    void anUnknownTokenIsA410() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verifyNoInteractions(rateLimiter, userRepository, twoFactorService, signInService);
    }

    @Test
    void aConsumedTokenCantBeUsedTwice() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
        verifyNoInteractions(signInService);
    }

    @Test
    void aDeactivatedAccountIsRefusedAtTheSecondStep() {
        final var user = user();
        user.setActive(false);
        givenAPendingLogin(new PendingLogin(7L, false), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(DisabledException.class);
        verifyNoInteractions(signInService);
    }

    @Test
    void anUnverifiedAccountIsRefusedAtTheSecondStep() {
        final var user = user();
        user.setEmailVerified(false);
        givenAPendingLogin(new PendingLogin(7L, false), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(EmailNotVerifiedException.class)
                .hasMessage("Please verify your email before logging in.");
        verifyNoInteractions(signInService);
    }

    @Test
    void aBlockedNetworkIsRefusedBeforeTheTokenIsRead() {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Request has been blocked");
        verifyNoInteractions(pendingLoginStore, rateLimiter, userRepository, twoFactorService, signInService);
    }

    @Test
    void aLockedAccountIsRefusedBeforeTheCodeIsChecked() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(rateLimiter.tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES)).thenReturn(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user()));
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(false);
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Too many invalid two-factor codes. Try again later.");
        verifyNoInteractions(twoFactorService, signInService);
    }

    @Test
    void aDeletedAccountIsA410() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(rateLimiter.tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES)).thenReturn(true);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verifyNoInteractions(twoFactorService, signInService);
    }

    @Test
    void anUnavailableAttemptCounterIsSignInUnavailable() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(rateLimiter.tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES))
                .thenThrow(new RateLimiterUnavailableException(new QueryTimeoutException("down")));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInUnavailableException.class)
                .hasMessage("Two-step sign-in isn't available right now. Try again in a minute.");
        verifyNoInteractions(userRepository, twoFactorService, signInService);
    }

    private void givenAPendingLogin(final PendingLogin pendingLogin, final User user) {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(false);
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(pendingLogin));
        when(rateLimiter.tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES)).thenReturn(true);
        when(userRepository.findById(pendingLogin.userId())).thenReturn(Optional.of(user));
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(false);
    }

    private static TotpSecondStepDto totp(final String code) {
        return TotpSecondStepDto.builder().pendingToken(TOKEN).code(code).build();
    }

    private static User user() {
        return User.builder()
                .id(7L)
                .email("user@example.com")
                .active(true)
                .emailVerified(true)
                .twoFactorEnabled(true)
                .twoStepEnabled(true)
                .build();
    }

}
