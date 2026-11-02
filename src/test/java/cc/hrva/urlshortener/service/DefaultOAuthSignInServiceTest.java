package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.impl.DefaultOAuthSignInService;
import cc.hrva.urlshortener.service.signin.OAuthSignIn;
import cc.hrva.urlshortener.service.signin.OAuthSignInCodes;
import cc.hrva.urlshortener.service.signin.SignInMethodGuard;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOAuthSignInServiceTest {

    private static final String CLIENT_IP = "203.0.113.9";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private OAuthSignInCodes signInCodes;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccountLock accountLock;
    @Mock
    private SignInMethodGuard signInMethodGuard;
    @Mock
    private SignInService signInService;
    @Mock
    private HttpServletRequest request;

    private OAuthSignInService service;

    @BeforeEach
    void setUp() {
        service = new DefaultOAuthSignInService(clientIpResolver, loginAttemptService, signInCodes, userRepository, accountLock,
                signInMethodGuard, signInService);
        lenient().when(clientIpResolver.getClientIp(request)).thenReturn(CLIENT_IP);
        lenient().when(accountLock.lock(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(signInMethodGuard.stillOn(any(), any())).thenReturn(true);
    }

    @Test
    void aTwoStepAccountsCodeAnswersThePendingSecondStepWithNoToken() {
        final var user = User.builder().id(8L).email("two@hrva.cc").active(true).twoStepEnabled(true).build();
        final ResponseEntity<JWTTokenDto> pending = ResponseEntity.ok(JWTTokenDto.builder()
                .twoStepRequired(true).pendingToken("pending").methods(List.of(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE)).build());
        when(signInCodes.consume("code")).thenReturn(Optional.of(new OAuthSignIn(8L, SignInMethod.GOOGLE, CHALLENGE)));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));
        when(signInService.requireSecondStep(user, SignInMethod.GOOGLE, false)).thenReturn(pending);

        final var answer = service.exchange("code", VERIFIER, request);

        assertThat(answer).isSameAs(pending);
        assertThat(answer.getBody().getToken()).isNull();
        verify(signInService, never()).tokenFor(any(), anyBoolean());
    }

    @Test
    void aFreshCodeAnswersLikeASignInWithoutRecordingItTwice() {
        final var user = User.builder().id(7L).email("ana@hrva.cc").active(true).build();
        final ResponseEntity<JWTTokenDto> signedIn = ResponseEntity.ok(JWTTokenDto.builder().token("Bearer jwt").build());
        when(signInCodes.consume("code")).thenReturn(Optional.of(new OAuthSignIn(7L, SignInMethod.GITHUB, CHALLENGE)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(signInService.tokenFor(user, false)).thenReturn(signedIn);

        assertThat(service.exchange("code", VERIFIER, request)).isSameAs(signedIn);
        verify(signInService, never()).signIn(any(), any(), anyBoolean());
    }

    @Test
    void anUnknownOrUsedCodeHasExpiredAndCountsAsAFailedSignIn() {
        when(signInCodes.consume("used")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("used", VERIFIER, request))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage("Your sign-in expired. Sign in again.");
        verify(loginAttemptService).networkFailed(CLIENT_IP);
    }

    @Test
    void aDeactivatedAccountIsRefused() {
        when(signInCodes.consume("code")).thenReturn(Optional.of(new OAuthSignIn(7L, SignInMethod.GOOGLE, CHALLENGE)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).active(false).build()));

        assertThatThrownBy(() -> service.exchange("code", VERIFIER, request)).isInstanceOf(DisabledException.class);
    }

    @Test
    void aMethodTurnedOffSinceTheRedirectIsRefused() {
        final var user = User.builder().id(7L).active(true).build();
        when(signInCodes.consume("code")).thenReturn(Optional.of(new OAuthSignIn(7L, SignInMethod.GOOGLE, CHALLENGE)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(signInMethodGuard.stillOn(user, SignInMethod.GOOGLE)).thenReturn(false);

        assertThatThrownBy(() -> service.exchange("code", VERIFIER, request)).isInstanceOf(SignInExpiredException.class);
        verify(signInService, never()).tokenFor(any(), anyBoolean());
    }

    @Test
    void aCodeExchangedWithAnotherBrowsersVerifierIsSpentAndAnsweredLikeAnUnknownCode() {
        when(signInCodes.consume("code")).thenReturn(Optional.of(new OAuthSignIn(7L, SignInMethod.GOOGLE, CHALLENGE)));

        for (final var verifier : new String[] {"another-browsers-verifier-that-is-long-enough-to-pass", VERIFIER + "x", "", null}) {
            assertThatThrownBy(() -> service.exchange("code", verifier, request))
                    .isInstanceOf(SignInExpiredException.class)
                    .hasMessage("Your sign-in expired. Sign in again.");
        }

        verify(loginAttemptService, times(4)).networkFailed(CLIENT_IP);
        verify(userRepository, never()).findById(any());
        verify(signInService, never()).tokenFor(any(), anyBoolean());
        verify(signInService, never()).requireSecondStep(any(), any(), anyBoolean());
    }

    @Test
    void aCodeStoredWithoutAChallengeIsRefused() {
        when(signInCodes.consume("code")).thenReturn(Optional.of(new OAuthSignIn(7L, SignInMethod.GOOGLE, null)));

        assertThatThrownBy(() -> service.exchange("code", VERIFIER, request)).isInstanceOf(SignInExpiredException.class);
        verify(userRepository, never()).findById(any());
    }

}
