package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeySecondStepDto;
import cc.hrva.urlshortener.dto.PendingTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.PasskeySignInStatsRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultSecondStepService;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.signin.SignInMethodGuard;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.service.twostep.PendingLogin;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepLockMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.web.webauthn.api.Bytes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultSecondStepServiceTest {

    private static final String CLIENT_IP = "203.0.113.9";
    private static final String TOKEN = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q";
    private static final String ATTEMPTS_KEY = "pending-login:" + TOKEN;
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final String INVALID_CODE = "Invalid two-factor code";
    private static final String SIGN_IN_EXPIRED = "Your sign-in expired. Sign in again.";
    private static final String OUT_OF_ATTEMPTS = "Too many wrong codes. Sign in again to get 5 more tries.";
    private static final String NETWORK_BLOCKED = "Too many failed sign-ins from this network. Try again tomorrow.";
    private static final String ACCOUNT_LOCKED = "Too many wrong codes for this account. Try again in 24 hours, or sign in with a passkey.";
    private static final String ACCOUNT_LOCKED_WITHOUT_A_PASSKEY = "Too many wrong codes for this account. Try again in 24 hours.";
    private static final String CEREMONY_EXPIRED = "This request expired. Try again.";
    private static final String OPTIONS_KEY = RateLimiter.clientKey("passkey-second-step-options", CLIENT_IP);
    private static final String PASSWORDLESS_OPTIONS_KEY = RateLimiter.clientKey("passkey-options", CLIENT_IP);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String NO_PASSKEYS = "This account has no passkeys or security keys.";
    private static final String CEREMONY_ID = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n";
    private static final String CREDENTIAL_ID = Bytes.random().toBase64UrlString();
    private static final PasskeyCeremony SECOND_STEP_CEREMONY =
            new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, Bytes.random().toBase64UrlString(), 7L);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CREDENTIAL = JSON.createObjectNode().put("rawId", CREDENTIAL_ID);

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
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Mock
    private PasskeyCeremonyStore passkeyCeremonyStore;

    @Mock
    private PasskeyAssertions passkeyAssertions;

    @Mock
    private PasskeySignInStatsRepository passkeySignInStatsRepository;

    @Mock
    private AnalyticsDays analyticsDays;

    @Mock
    private HttpServletRequest request;

    @Mock
    private AccountLock accountLock;

    @Mock
    private SignInMethodGuard signInMethodGuard;

    private SecondStepService secondStepService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getPasskeys().setRpId("hrva.cc");
        final var passkeyOptionsFactory = new PasskeyOptionsFactory(appProperties);
        final var passkeyCeremonies = new PasskeyCeremonies(passkeyCeremonyStore, passkeyOptionsFactory, new PasskeyJson(), rateLimiter);
        secondStepService = new DefaultSecondStepService(clientIpResolver, loginAttemptService, pendingLoginStore,
                rateLimiter, userRepository, twoFactorService, signInService, passkeyCredentialRepository,
                passkeyCeremonies, passkeyCeremonyStore, passkeyOptionsFactory, passkeyAssertions,
                passkeySignInStatsRepository, analyticsDays, new TwoStepLockMessage(passkeyCredentialRepository), accountLock,
                signInMethodGuard);
        when(clientIpResolver.getClientIp(request)).thenReturn(CLIENT_IP);
        lenient().when(accountLock.lock(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(signInMethodGuard.stillOn(any(User.class), any())).thenReturn(true);
    }

    @Test
    void aCorrectTotpCodeSignsInWithTheRememberedChoice() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, true, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, true, SignInMethod.PASSWORD)));
        when(signInService.signIn(user, SignInMethod.PASSWORD, true)).thenReturn(signedIn);

        final var response = secondStepService.completeWithCode(totp("123456"), request);

        assertThat(response).isSameAs(signedIn);
        verify(loginAttemptService).loginSucceeded("2fa:7");
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void aSecondStepAfterAGitHubSignInSignsInWithGitHub() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.GITHUB), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.GITHUB)));
        when(signInService.signIn(user, SignInMethod.GITHUB, false)).thenReturn(signedIn);

        assertThat(secondStepService.completeWithCode(totp("123456"), request)).isSameAs(signedIn);
    }

    @Test
    void aRecoveryCodeSignsIn() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "ABCD-EF23")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(signInService.signIn(user, SignInMethod.PASSWORD, false)).thenReturn(signedIn);

        assertThat(secondStepService.completeWithCode(totp("ABCD-EF23"), request)).isSameAs(signedIn);
    }

    @Test
    void aWrongCodeCountsAgainstTheNetworkAndTheAccount() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("000000"), request))
                .isInstanceOf(TwoStepFailedException.class)
                .hasMessage(INVALID_CODE);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).consume(anyString());
        verify(pendingLoginStore, never()).discard(anyString());
        verify(signInService, never()).signIn(any(), any(), anyBoolean());
    }

    @Test
    void theSixthAttemptOnOneTokenIsRefusedAndTheTokenIsDiscarded() {
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user(), 6L);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(OUT_OF_ATTEMPTS);
        verify(pendingLoginStore).discard(TOKEN);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verifyNoInteractions(twoFactorService, signInService);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1 | Invalid two-factor code",
            "2 | Invalid two-factor code",
            "3 | Invalid two-factor code. 2 tries left.",
            "4 | Invalid two-factor code. 1 try left."
    })
    void aWrongCodeSaysHowManyTriesAreLeftOnceTwoOrFewerRemain(final long attempt, final String message) {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, attempt);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("000000"), request))
                .isInstanceOf(TwoStepFailedException.class)
                .hasMessage(message);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).discard(anyString());
    }

    @Test
    void theFifthWrongCodeUsesUpTheSignInAndSaysHowToGetMoreTries() {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 5L);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("000000"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(OUT_OF_ATTEMPTS);
        verify(pendingLoginStore).discard(TOKEN);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).consume(anyString());
        verifyNoInteractions(signInService);
    }

    @Test
    void theRightCodeOnTheFifthAttemptStillSignsIn() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 5L);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(signInService.signIn(user, SignInMethod.PASSWORD, false)).thenReturn(signedIn);

        assertThat(secondStepService.completeWithCode(totp("123456"), request)).isSameAs(signedIn);
        verify(pendingLoginStore, never()).discard(anyString());
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2 | That passkey or security key couldn't be verified. Try again.",
            "3 | That passkey or security key couldn't be verified. Try again. 2 tries left.",
            "4 | That passkey or security key couldn't be verified. Try again. 1 try left."
    })
    void aRefusedPasskeySaysHowManyTriesAreLeftOnceTwoOrFewerRemain(final long attempt, final String message) {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, attempt);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenThrow(new PasskeyVerificationException(COULD_NOT_VERIFY));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(message);
        verify(pendingLoginStore, never()).discard(anyString());
    }

    @Test
    void anUnreadablePasskeyResponseAlsoSaysHowManyTriesAreLeft() {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 4L);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenThrow(new ApiException(PasskeyJson.UNREADABLE));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(ApiException.class)
                .hasMessage("That passkey response couldn't be read. Try again. 1 try left.");
    }

    @Test
    void theFifthRefusedPasskeyUsesUpTheSignIn() {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 5L);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenThrow(new PasskeyVerificationException(COULD_NOT_VERIFY));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(OUT_OF_ATTEMPTS);
        verify(pendingLoginStore).discard(TOKEN);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verifyNoInteractions(signInService, passkeySignInStatsRepository);
    }

    @Test
    void anExpiredCeremonyOnTheFifthAttemptKeepsTheSignInForTheNextTry() {
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user(), 5L);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(CEREMONY_EXPIRED);
        verify(pendingLoginStore, never()).discard(anyString());
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
    void anUnknownTokenCountsAgainstTheNetwork() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService, never()).loginFailed(startsWith("2fa:"));
    }

    @Test
    void aConsumedTokenCantBeUsedTwice() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
        verify(loginAttemptService, never()).loginFailed(anyString());
        verifyNoInteractions(signInService);
    }

    @Test
    void aDeactivatedAccountIsRefusedAtTheSecondStep() {
        final var user = user();
        user.setActive(false);
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(DisabledException.class);
        verifyNoInteractions(signInService);
    }

    @Test
    void anUnverifiedAccountIsRefusedAtTheSecondStep() {
        final var user = user();
        user.setEmailVerified(false);
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(EmailNotVerifiedException.class)
                .hasMessage("Please verify your email before logging in.");
        verifyNoInteractions(signInService);
    }

    @Test
    void aPasswordRemovedAfterTheFirstStepStopsTheSecondStep() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(signInMethodGuard.stillOn(user, SignInMethod.PASSWORD)).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);

        final var order = inOrder(pendingLoginStore, accountLock, signInMethodGuard);
        order.verify(pendingLoginStore).consume(TOKEN);
        order.verify(accountLock).lock(user);
        order.verify(signInMethodGuard).stillOn(user, SignInMethod.PASSWORD);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
        verifyNoInteractions(signInService);
    }

    @Test
    void aProviderTurnedOffAfterTheFirstStepStopsTheSecondStepWithAPasskey() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.GITHUB), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenReturn(user);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.GITHUB)));
        when(signInMethodGuard.stillOn(user, SignInMethod.GITHUB)).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);

        verifyNoInteractions(signInService, passkeySignInStatsRepository);
    }

    @Test
    void aBlockedNetworkIsRefusedBeforeTheTokenIsRead() {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(NETWORK_BLOCKED);
        verifyNoInteractions(pendingLoginStore, rateLimiter, userRepository, twoFactorService, signInService);
    }

    @Test
    void aLockedAccountIsRefusedBeforeTheCodeIsCheckedOrAnAttemptIsUsed() {
        final var user = user();
        givenALockedAccount(user);
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(synced()));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(ACCOUNT_LOCKED);
        verifyNoInteractions(twoFactorService, signInService);
        verify(rateLimiter, never()).increment(eq(ATTEMPTS_KEY), any());
    }

    @ParameterizedTest
    @MethodSource("passkeysAndTheLockText")
    void theLockTextOffersAPasskeyOnlyWhenTheAccountHasOneThatSignsInAlone(final List<PasskeyCredential> passkeys, final String message) {
        final var user = user();
        givenALockedAccount(user);
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(passkeys);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(message);
    }

    @Test
    void aFifthWrongCodeThatLocksTheAccountSaysTheAccountIsLocked() {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 5L);
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(false, true);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(synced()));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("000000"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(ACCOUNT_LOCKED);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(pendingLoginStore, never()).discard(anyString());
        verifyNoInteractions(signInService);
    }

    @Test
    void aThirdWrongCodeThatLocksTheAccountSaysSoInsteadOfTheTriesLeft() {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 3L);
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(false, true);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("000000"), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(ACCOUNT_LOCKED_WITHOUT_A_PASSKEY);
    }

    @Test
    void aFifthRefusedPasskeyThatLocksTheAccountSaysTheAccountIsLocked() {
        final var user = user();
        givenAPendingLoginOnAttempt(new PendingLogin(7L, false, SignInMethod.PASSWORD), user, 5L);
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(false, true);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenThrow(new PasskeyVerificationException(COULD_NOT_VERIFY));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user))
                .thenReturn(List.of(PasskeyCredential.builder().credentialId(CREDENTIAL_ID).discoverable(false).build()));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(ACCOUNT_LOCKED_WITHOUT_A_PASSKEY);
        verify(pendingLoginStore, never()).discard(anyString());
        verifyNoInteractions(signInService, passkeySignInStatsRepository);
    }

    @Test
    void aDeletedAccountIsA410() {
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verifyNoInteractions(twoFactorService, signInService);
    }

    @Test
    void anUnavailableAttemptCounterIsSignInUnavailable(final CapturedOutput output) {
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user());
        when(rateLimiter.increment(ATTEMPTS_KEY, FIVE_MINUTES))
                .thenThrow(new RateLimiterUnavailableException(new QueryTimeoutException("down")));

        assertThatThrownBy(() -> secondStepService.completeWithCode(totp("123456"), request))
                .isInstanceOf(SignInUnavailableException.class)
                .hasMessage("Two-step sign-in isn't available right now. Try again in a minute.");
        verify(loginAttemptService, never()).loginFailed(anyString());
        verifyNoInteractions(twoFactorService, signInService);
        assertThat(output).containsOnlyOnce("Second step refused, attempt counter unavailable user id=7").doesNotContain(CLIENT_IP, TOKEN);
    }

    @Test
    void passkeyOptionsStartASecondStepCeremonyForTheAccountsPasskeys() {
        final var user = user();
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(securityKey()));
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(true);
        final var ceremony = ArgumentCaptor.forClass(PasskeyCeremony.class);
        when(passkeyCeremonyStore.save(ceremony.capture())).thenReturn(CEREMONY_ID);

        final var response = secondStepService.passkeyOptions(pendingToken(), request);

        assertThat(response.ceremonyId()).isEqualTo(CEREMONY_ID);
        assertThat(ceremony.getValue().purpose()).isEqualTo(PasskeyCeremonyPurpose.SECOND_STEP);
        assertThat(ceremony.getValue().userId()).isEqualTo(7L);
        assertThat(ceremony.getValue().challenge()).hasSize(43);
        assertThat(JSON.readTree(JSON.writeValueAsString(response.options()))).isEqualTo(JSON.readTree("""
                {"challenge":"%s","timeout":300000,"rpId":"hrva.cc","allowCredentials":[{"type":"public-key","id":"%s","transports":["usb","nfc"]}],"userVerification":"preferred","extensions":{}}
                """.formatted(ceremony.getValue().challenge(), CREDENTIAL_ID)));
        verify(rateLimiter).tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES);
        verify(rateLimiter, never()).tryAcquire(eq(PASSWORDLESS_OPTIONS_KEY), anyInt(), any());
        verify(rateLimiter, never()).increment(eq(ATTEMPTS_KEY), any());
        verifyNoInteractions(passkeyAssertions, signInService);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void passkeyOptionsForAnAccountWithoutPasskeysAre400() {
        final var user = user();
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of());
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(ApiException.class)
                .hasMessage(NO_PASSKEYS);
        verify(rateLimiter, never()).increment(eq(ATTEMPTS_KEY), any());
        verifyNoInteractions(passkeyCeremonyStore);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void passkeyOptionsAfterThePendingTokenExpiredAre410() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(true);
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService, never()).loginFailed(startsWith("2fa:"));
        verify(rateLimiter, never()).increment(eq(ATTEMPTS_KEY), any());
        verifyNoInteractions(userRepository, passkeyCeremonyStore);
    }

    @Test
    void passkeyOptionsForABlockedNetworkAre403() {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(NETWORK_BLOCKED);
        verifyNoInteractions(rateLimiter, pendingLoginStore, userRepository, passkeyCeremonyStore);
    }

    @Test
    void passkeyOptionsHaveTheirOwnLimitOf30Per10MinutesPerNetwork() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Too many passkey sign-in attempts from this network. Try again in a few minutes.");
        verify(rateLimiter, never()).tryAcquire(eq(PASSWORDLESS_OPTIONS_KEY), anyInt(), any());
        verifyNoInteractions(pendingLoginStore, userRepository, passkeyCeremonyStore);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void passkeyOptionsWithTheLimiterDownArePasskeysUnavailable(final CapturedOutput output) {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES))
                .thenThrow(new RateLimiterUnavailableException(new QueryTimeoutException("down")));

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(SignInUnavailableException.class)
                .hasMessage("Passkeys aren't available right now. Try again in a minute.");
        verifyNoInteractions(pendingLoginStore, userRepository, passkeyCeremonyStore);
        verify(loginAttemptService, never()).loginFailed(anyString());
        assertThat(output).containsOnlyOnce("Passkey options refused, rate limiter unavailable name=passkey-second-step-options")
                .doesNotContain(CLIENT_IP, TOKEN);
    }

    @Test
    void aVerifiedPasskeyFinishesTheSecondStepAndThenCountsTheDay() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, true, SignInMethod.PASSWORD), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenReturn(user);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, true, SignInMethod.PASSWORD)));
        when(signInService.signIn(user, SignInMethod.PASSWORD, true)).thenReturn(signedIn);
        when(analyticsDays.today()).thenReturn(TODAY);

        final var response = secondStepService.completeWithPasskey(passkey(), request);

        assertThat(response).isSameAs(signedIn);
        final var inOrder = inOrder(rateLimiter, passkeyCeremonyStore, passkeyAssertions, pendingLoginStore,
                loginAttemptService, signInService, passkeySignInStatsRepository);
        inOrder.verify(rateLimiter).increment(ATTEMPTS_KEY, FIVE_MINUTES);
        inOrder.verify(passkeyCeremonyStore).consume(CEREMONY_ID);
        inOrder.verify(passkeyAssertions).verify(SECOND_STEP_CEREMONY, CREDENTIAL, user);
        inOrder.verify(pendingLoginStore).consume(TOKEN);
        inOrder.verify(loginAttemptService).loginSucceeded("2fa:7");
        inOrder.verify(signInService).signIn(user, SignInMethod.PASSWORD, true);
        inOrder.verify(passkeySignInStatsRepository).increment(TODAY);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void aRefusedPasskeyCountsAgainstTheNetworkAndTheAccount() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenThrow(new PasskeyVerificationException(COULD_NOT_VERIFY));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).consume(anyString());
        verifyNoInteractions(signInService, passkeySignInStatsRepository);
    }

    @Test
    void anUnreadablePasskeyResponseCountsAgainstTheNetworkAndTheAccount() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenThrow(new ApiException(PasskeyJson.UNREADABLE));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(ApiException.class)
                .hasMessage("That passkey response couldn't be read. Try again.");
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).consume(anyString());
        verifyNoInteractions(signInService, passkeySignInStatsRepository);
    }

    @ParameterizedTest
    @MethodSource("ceremoniesThatDontFinishThisSignIn")
    void aCeremonyThatIsGoneOrNotForThisSignInIsA410(final Optional<PasskeyCeremony> ceremony) {
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user());
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(ceremony);

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(CEREMONY_EXPIRED);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService, never()).loginFailed("2fa:7");
        verify(pendingLoginStore, never()).consume(anyString());
        verifyNoInteractions(passkeyAssertions, signInService, passkeySignInStatsRepository);
    }

    @Test
    void aLockedAccountIsRefusedBeforeTheCeremonyIsUsed() {
        final var user = user();
        givenALockedAccount(user);
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(synced()));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(ACCOUNT_LOCKED);
        verifyNoInteractions(passkeyCeremonyStore, passkeyAssertions, signInService);
        verify(rateLimiter, never()).increment(eq(ATTEMPTS_KEY), any());
    }

    @Test
    void aDeactivatedAccountIsRefusedAfterThePasskeyAndNotCountedAsASignIn() {
        final var user = user();
        user.setActive(false);
        givenAPendingLogin(new PendingLogin(7L, false, SignInMethod.PASSWORD), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenReturn(user);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(DisabledException.class);
        verifyNoInteractions(signInService, passkeySignInStatsRepository);
    }

    private void givenALockedAccount(final User user) {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(false);
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false, SignInMethod.PASSWORD)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(true);
    }

    private void givenAPendingLogin(final PendingLogin pendingLogin, final User user) {
        givenAPendingLoginOnAttempt(pendingLogin, user, 1L);
    }

    private void givenAPendingLoginOnAttempt(final PendingLogin pendingLogin, final User user, final long attempt) {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(false);
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(pendingLogin));
        when(rateLimiter.increment(ATTEMPTS_KEY, FIVE_MINUTES)).thenReturn(attempt);
        when(userRepository.findById(pendingLogin.userId())).thenReturn(Optional.of(user));
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(false);
    }

    private static TotpSecondStepDto totp(final String code) {
        return TotpSecondStepDto.builder().pendingToken(TOKEN).code(code).build();
    }

    private static PendingTokenDto pendingToken() {
        return new PendingTokenDto(TOKEN);
    }

    private static PasskeySecondStepDto passkey() {
        return PasskeySecondStepDto.builder()
                .pendingToken(TOKEN)
                .ceremonyId(CEREMONY_ID)
                .credential(CREDENTIAL)
                .build();
    }

    private static PasskeyCredential securityKey() {
        return PasskeyCredential.builder()
                .credentialId(CREDENTIAL_ID)
                .transports("usb,nfc")
                .build();
    }

    private static PasskeyCredential synced() {
        return PasskeyCredential.builder().credentialId("synced").backupEligible(true).discoverable(true).build();
    }

    private static Stream<Arguments> passkeysAndTheLockText() {
        final var secondStepOnly = PasskeyCredential.builder().credentialId("second-step-only").discoverable(false).build();
        final var deviceBound = PasskeyCredential.builder().credentialId("device-bound").discoverable(true).build();
        final var unknown = PasskeyCredential.builder().credentialId("unknown").build();

        return Stream.of(
                Arguments.of(List.of(), ACCOUNT_LOCKED_WITHOUT_A_PASSKEY),
                Arguments.of(List.of(secondStepOnly), ACCOUNT_LOCKED_WITHOUT_A_PASSKEY),
                Arguments.of(List.of(synced()), ACCOUNT_LOCKED),
                Arguments.of(List.of(deviceBound), ACCOUNT_LOCKED),
                Arguments.of(List.of(unknown), ACCOUNT_LOCKED_WITHOUT_A_PASSKEY),
                Arguments.of(List.of(unknown, deviceBound), ACCOUNT_LOCKED),
                Arguments.of(List.of(secondStepOnly, synced()), ACCOUNT_LOCKED));
    }

    private static Stream<Optional<PasskeyCeremony>> ceremoniesThatDontFinishThisSignIn() {
        return Stream.of(
                Optional.empty(),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, SECOND_STEP_CEREMONY.challenge(), null)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.TWO_STEP_DISABLE, SECOND_STEP_CEREMONY.challenge(), 7L)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, SECOND_STEP_CEREMONY.challenge(), 8L)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, SECOND_STEP_CEREMONY.challenge(), null)));
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
