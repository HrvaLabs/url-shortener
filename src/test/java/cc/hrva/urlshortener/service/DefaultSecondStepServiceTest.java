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
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
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
import cc.hrva.urlshortener.service.twostep.PendingLogin;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private static final String CEREMONY_EXPIRED = "This request expired. Try again.";
    private static final String OPTIONS_KEY = RateLimiter.clientKey("passkey-options", CLIENT_IP);
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
                passkeySignInStatsRepository, analyticsDays);
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
        verify(loginAttemptService).loginFailed(CLIENT_IP);
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
        givenAPendingLogin(new PendingLogin(7L, false), user);
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
        verify(loginAttemptService).loginFailed(CLIENT_IP);
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
        verify(loginAttemptService, never()).loginFailed(anyString());
        verifyNoInteractions(userRepository, twoFactorService, signInService);
    }

    @Test
    void passkeyOptionsStartASecondStepCeremonyForTheAccountsPasskeys() {
        final var user = user();
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
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
        verify(rateLimiter, never()).tryAcquire(eq(ATTEMPTS_KEY), anyInt(), any());
        verifyNoInteractions(passkeyAssertions, signInService);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void passkeyOptionsForAnAccountWithoutPasskeysAre400() {
        final var user = user();
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of());
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(ApiException.class)
                .hasMessage(NO_PASSKEYS);
        verify(rateLimiter, never()).tryAcquire(eq(ATTEMPTS_KEY), anyInt(), any());
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
        verify(rateLimiter, never()).tryAcquire(eq(ATTEMPTS_KEY), anyInt(), any());
        verifyNoInteractions(userRepository, passkeyCeremonyStore);
    }

    @Test
    void passkeyOptionsForABlockedNetworkAre403() {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Request has been blocked");
        verifyNoInteractions(rateLimiter, pendingLoginStore, userRepository, passkeyCeremonyStore);
    }

    @Test
    void passkeyOptionsShareThePasswordlessLimitOf30Per10MinutesPerNetwork() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(false);

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Too many passkey sign-in attempts from this network. Try again in a few minutes.");
        verifyNoInteractions(pendingLoginStore, userRepository, passkeyCeremonyStore);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void passkeyOptionsWithTheLimiterDownArePasskeysUnavailable() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES))
                .thenThrow(new RateLimiterUnavailableException(new QueryTimeoutException("down")));

        assertThatThrownBy(() -> secondStepService.passkeyOptions(pendingToken(), request))
                .isInstanceOf(SignInUnavailableException.class)
                .hasMessage("Passkeys aren't available right now. Try again in a minute.");
        verifyNoInteractions(pendingLoginStore, userRepository, passkeyCeremonyStore);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void aVerifiedPasskeyFinishesTheSecondStepAndThenCountsTheDay() {
        final var user = user();
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        givenAPendingLogin(new PendingLogin(7L, true), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenReturn(user);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, true)));
        when(signInService.signIn(user, true)).thenReturn(signedIn);
        when(analyticsDays.today()).thenReturn(TODAY);

        final var response = secondStepService.completeWithPasskey(passkey(), request);

        assertThat(response).isSameAs(signedIn);
        final var inOrder = inOrder(rateLimiter, passkeyCeremonyStore, passkeyAssertions, pendingLoginStore,
                loginAttemptService, signInService, passkeySignInStatsRepository);
        inOrder.verify(rateLimiter).tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES);
        inOrder.verify(passkeyCeremonyStore).consume(CEREMONY_ID);
        inOrder.verify(passkeyAssertions).verify(SECOND_STEP_CEREMONY, CREDENTIAL, user);
        inOrder.verify(pendingLoginStore).consume(TOKEN);
        inOrder.verify(loginAttemptService).loginSucceeded("2fa:7");
        inOrder.verify(signInService).signIn(user, true);
        inOrder.verify(passkeySignInStatsRepository).increment(TODAY);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void aRefusedPasskeyCountsAgainstTheNetworkAndTheAccount() {
        final var user = user();
        givenAPendingLogin(new PendingLogin(7L, false), user);
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
        givenAPendingLogin(new PendingLogin(7L, false), user);
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
        givenAPendingLogin(new PendingLogin(7L, false), user());
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
        when(pendingLoginStore.find(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));
        when(rateLimiter.tryAcquire(ATTEMPTS_KEY, 5, FIVE_MINUTES)).thenReturn(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user()));
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(false);
        when(loginAttemptService.isBlocked("2fa:7")).thenReturn(true);

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Too many invalid two-factor codes. Try again later.");
        verifyNoInteractions(passkeyCeremonyStore, passkeyAssertions, signInService);
    }

    @Test
    void aDeactivatedAccountIsRefusedAfterThePasskeyAndNotCountedAsASignIn() {
        final var user = user();
        user.setActive(false);
        givenAPendingLogin(new PendingLogin(7L, false), user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(SECOND_STEP_CEREMONY));
        when(passkeyAssertions.verify(SECOND_STEP_CEREMONY, CREDENTIAL, user)).thenReturn(user);
        when(pendingLoginStore.consume(TOKEN)).thenReturn(Optional.of(new PendingLogin(7L, false)));

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(passkey(), request))
                .isInstanceOf(DisabledException.class);
        verifyNoInteractions(signInService, passkeySignInStatsRepository);
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
