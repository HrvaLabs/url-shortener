package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeySignInDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeySignInStatsRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultPasskeySignInService;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.webauthn.api.Bytes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPasskeySignInServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CLIENT_IP = "203.0.113.9";
    private static final String CEREMONY_ID = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n";
    private static final String CHALLENGE = Bytes.random().toBase64UrlString();
    private static final String OPTIONS_KEY = RateLimiter.clientKey("passkey-options", CLIENT_IP);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String EXPIRED = "This request expired. Try again.";

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private PasskeyCeremonyStore passkeyCeremonyStore;

    @Mock
    private PasskeyAssertions passkeyAssertions;

    @Mock
    private PasskeySignInStatsRepository passkeySignInStatsRepository;

    @Mock
    private AnalyticsDays analyticsDays;

    @Mock
    private SignInService signInService;

    @Mock
    private HttpServletRequest request;

    private PasskeySignInService passkeySignInService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getPasskeys().setRpId("hrva.cc");
        passkeySignInService = new DefaultPasskeySignInService(clientIpResolver, loginAttemptService, rateLimiter,
                passkeyCeremonyStore, new PasskeyOptionsFactory(appProperties), new PasskeyJson(), passkeyAssertions,
                passkeySignInStatsRepository, analyticsDays, signInService);
        when(clientIpResolver.getClientIp(request)).thenReturn(CLIENT_IP);
    }

    @Test
    void optionsStartASignInCeremonyForAnyAccount() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(true);
        final var ceremony = ArgumentCaptor.forClass(PasskeyCeremony.class);
        when(passkeyCeremonyStore.save(ceremony.capture())).thenReturn(CEREMONY_ID);

        final var response = passkeySignInService.options(request);

        assertThat(response.ceremonyId()).isEqualTo(CEREMONY_ID);
        assertThat(ceremony.getValue().purpose()).isEqualTo(PasskeyCeremonyPurpose.SIGN_IN);
        assertThat(ceremony.getValue().userId()).isNull();
        assertThat(ceremony.getValue().challenge()).hasSize(43);
        assertThat(JSON.readTree(JSON.writeValueAsString(response.options()))).isEqualTo(JSON.readTree("""
                {"challenge":"%s","timeout":300000,"rpId":"hrva.cc","allowCredentials":[],"userVerification":"required","extensions":{}}
                """.formatted(ceremony.getValue().challenge())));
    }

    @Test
    void optionsOverTheLimitAre429WithItsText() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES)).thenReturn(false);

        assertThatThrownBy(() -> passkeySignInService.options(request))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Too many passkey sign-in attempts from this network. Try again in a few minutes.");
        verifyNoInteractions(passkeyCeremonyStore);
    }

    @Test
    void optionsWithoutRedisAre503() {
        when(rateLimiter.tryAcquire(OPTIONS_KEY, 30, TEN_MINUTES))
                .thenThrow(new RateLimiterUnavailableException(new QueryTimeoutException("down")));

        assertThatThrownBy(() -> passkeySignInService.options(request))
                .isInstanceOf(SignInUnavailableException.class)
                .hasMessage("Passkeys aren't available right now. Try again in a minute.");
        verifyNoInteractions(passkeyCeremonyStore);
    }

    @Test
    void aBlockedNetworkIsRefusedBeforeTheCeremonyIsRead() {
        when(loginAttemptService.isBlocked(CLIENT_IP)).thenReturn(true);

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(true), request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Request has been blocked");
        verifyNoInteractions(passkeyCeremonyStore, passkeyAssertions, passkeySignInStatsRepository, signInService);
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void aFailedAssertionCountsAgainstTheNetwork() {
        final var ceremony = signInCeremony();
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(passkeyAssertions.verify(any(), any(), isNull())).thenThrow(new PasskeyVerificationException(COULD_NOT_VERIFY));

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(true), request))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
        verifyNoInteractions(passkeySignInStatsRepository, signInService);
    }

    @Test
    void anUnreadableCredentialCountsAgainstTheNetwork() {
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(signInCeremony()));
        when(passkeyAssertions.verify(any(), any(), isNull())).thenThrow(new ApiException(PasskeyJson.UNREADABLE));

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(true), request))
                .isInstanceOf(ApiException.class)
                .hasMessage(PasskeyJson.UNREADABLE);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verifyNoInteractions(passkeySignInStatsRepository, signInService);
    }

    @Test
    void anExpiredCeremonyIsA410AndCountsAgainstTheNetwork() {
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(true), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verifyNoInteractions(passkeyAssertions, passkeySignInStatsRepository, signInService);
    }

    @Test
    void aCeremonyForAnotherPurposeIsA410() {
        when(passkeyCeremonyStore.consume(CEREMONY_ID))
                .thenReturn(Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, CHALLENGE, 7L)));

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(true), request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verifyNoInteractions(passkeyAssertions, signInService);
    }

    @Test
    void anUnavailableCeremonyStoreIsA503AndNotAFailedAttempt() {
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenThrow(new SignInUnavailableException(
                "Passkeys aren't available right now. Try again in a minute.", new QueryTimeoutException("down")));

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(true), request))
                .isInstanceOf(SignInUnavailableException.class);
        verify(loginAttemptService, never()).loginFailed(anyString());
        verifyNoInteractions(passkeyAssertions, signInService);
    }

    @Test
    void aVerifiedPasskeySignsInCountsTheDayAndSkipsTheSecondStep() {
        final var ceremony = signInCeremony();
        final var user = user();
        user.setTwoStepEnabled(true);
        final var signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build());
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(passkeyAssertions.verify(any(), any(), isNull())).thenReturn(user);
        when(analyticsDays.today()).thenReturn(TODAY);
        when(signInService.signIn(user, true)).thenReturn(signedIn);

        assertThat(passkeySignInService.verify(signIn(true), request)).isSameAs(signedIn);

        final var order = inOrder(passkeyAssertions, loginAttemptService, passkeySignInStatsRepository, signInService);
        order.verify(passkeyAssertions).verify(ceremony, credential(), null);
        order.verify(loginAttemptService).loginSucceeded(CLIENT_IP);
        order.verify(passkeySignInStatsRepository).increment(TODAY);
        order.verify(signInService).signIn(user, true);
        verify(signInService, never()).requireSecondStep(any(), anyBoolean());
        verify(loginAttemptService, never()).loginFailed(anyString());
    }

    @Test
    void withoutRememberMeTheShortTokenIsIssued() {
        final var user = user();
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(signInCeremony()));
        when(passkeyAssertions.verify(any(), any(), isNull())).thenReturn(user);
        when(analyticsDays.today()).thenReturn(TODAY);

        passkeySignInService.verify(signIn(null), request);

        verify(signInService).signIn(user, false);
    }

    private static PasskeyCeremony signInCeremony() {
        return new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, CHALLENGE, null);
    }

    private static PasskeySignInDto signIn(final Boolean rememberMe) {
        return PasskeySignInDto.builder()
                .ceremonyId(CEREMONY_ID)
                .credential(credential())
                .rememberMe(rememberMe)
                .build();
    }

    private static JsonNode credential() {
        return JSON.readTree("""
                {"id":"a2V5","rawId":"a2V5","type":"public-key","response":{"authenticatorData":"e30","clientDataJSON":"e30","signature":"e30"}}""");
    }

    private static User user() {
        return User.builder()
                .id(7L)
                .email("user@localhost")
                .authProvider("local")
                .active(true)
                .emailVerified(true)
                .authorities(List.of())
                .build();
    }

}
