package cc.hrva.urlshortener.service;

import jakarta.servlet.http.HttpServletRequest;
import cc.hrva.urlshortener.converter.UserRegisterDtoToUserConverter;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.dto.LoginDto;
import cc.hrva.urlshortener.dto.UserRegisterDto;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.impl.DefaultAuthService;
import cc.hrva.urlshortener.validator.UserValidator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultAuthServiceTest {

    private static final String PENDING_TOKEN = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q";

    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private UserValidator userValidator;

    @Mock
    private SignInService signInService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private cc.hrva.urlshortener.repository.UserRepository userRepository;

    @Mock
    private cc.hrva.urlshortener.service.SendingEmailService sendingEmailService;

    @Mock
    private cc.hrva.urlshortener.service.VerificationTokenService verificationTokenService;

    @Mock
    private cc.hrva.urlshortener.service.TwoFactorService twoFactorService;

    @Mock
    private AuthenticationManagerBuilder authenticationManagerBuilder;

    @Mock
    private UserRegisterDtoToUserConverter userRegisterDtoToUserConverter;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private HttpServletRequest request;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        this.authService = new DefaultAuthService(userService, userValidator, signInService, userRepository,
                clientIpResolver, loginAttemptService, sendingEmailService, twoFactorService,
                verificationTokenService, authenticationManagerBuilder, userRegisterDtoToUserConverter);
    }

    @Test
    void shouldRegisterUser() {
        final var registerDto = UserRegisterDto.builder().email("test@example.com").password("password123").build();
        final var user = User.builder().email("test@example.com").build();

        when(userRegisterDtoToUserConverter.convert(registerDto)).thenReturn(user);
        when(userService.register(user)).thenReturn(user);

        final var result = authService.register(registerDto);

        assertThat(result).isEqualTo("test@example.com");
        verify(userValidator).checkRegistrationEnabled();
        verify(userValidator).checkEmailUniqueness("test@example.com");
    }

    @Test
    void shouldLoginSuccessfully(final CapturedOutput output) {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).build();
        final var signedIn = signedIn();

        givenTheRightPassword(user);
        when(signInService.signIn(user, false)).thenReturn(signedIn);

        final var response = authService.login(loginDto, request);

        assertThat(response).isSameAs(signedIn);
        final var credentials = ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(credentials.capture());
        assertThat(credentials.getValue().getName()).isEqualTo("test@example.com");
        assertThat(credentials.getValue().getCredentials()).isEqualTo("password123");
        assertThat(output).contains("User logged in email=t***@example.com").doesNotContain("test@example.com");
    }

    @Test
    void anAccountWithoutTwoStepGetsItsJwtDirectly() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").rememberMe(true).build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(false).twoStepEnabled(false).build();
        final var signedIn = signedIn();

        givenTheRightPassword(user);
        when(signInService.signIn(user, true)).thenReturn(signedIn);

        assertThat(authService.login(loginDto, request)).isSameAs(signedIn);
        verify(signInService, never()).requireSecondStep(any(), anyBoolean());
        verifyNoInteractions(twoFactorService);
    }

    @Test
    void shouldThrowWhenBlocked() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Request has been blocked");
        verifyNoInteractions(authenticationManagerBuilder, signInService);
    }

    @Test
    void shouldBlockLoginWhenEmailNotVerified() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(false).twoStepEnabled(true).build();

        givenTheRightPassword(user);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(cc.hrva.urlshortener.exception.EmailNotVerifiedException.class)
                .hasMessage("Please verify your email before logging in.");
        verifyNoInteractions(signInService);
    }

    @Test
    void aTwoStepAccountGetsAPendingTokenAndNoJwt() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").rememberMe(true).build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(true).twoStepEnabled(true).build();
        final var pending = ResponseEntity.ok(JWTTokenDto.twoStepRequired(PENDING_TOKEN, List.of(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE)));

        givenTheRightPassword(user);
        when(signInService.requireSecondStep(user, true)).thenReturn(pending);

        final var response = authService.login(loginDto, request);

        assertThat(response).isSameAs(pending);
        assertThat(response.getBody().getTwoFactorRequired()).isTrue();
        assertThat(response.getBody().getTwoStepRequired()).isTrue();
        assertThat(response.getBody().getToken()).isNull();
        verify(signInService, never()).signIn(any(), anyBoolean());
        verifyNoInteractions(twoFactorService);
        verify(loginAttemptService, never()).isBlocked("2fa:1");
    }

    @Test
    void registrationRunsInAWritableTransaction() throws NoSuchMethodException {
        final var transactional = cc.hrva.urlshortener.service.impl.DefaultAuthService.class
                .getMethod("register", cc.hrva.urlshortener.dto.UserRegisterDto.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void shouldLoginWhenTwoFactorCodeValid() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("123456").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(true).twoStepEnabled(true).build();
        final var signedIn = signedIn();

        givenTheRightPassword(user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(signInService.signIn(user, false)).thenReturn(signedIn);

        final var response = authService.login(loginDto, request);

        assertThat(response).isSameAs(signedIn);
        verify(loginAttemptService).loginSucceeded("2fa:1");
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void theLegacyCodeOnLoginStillSignsInForOneRelease(final CapturedOutput output) {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").rememberMe(true).code("123456").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(true).twoStepEnabled(true).twoFactorSecret("JBSWY3DPEHPK3PXP").build();
        final var signedIn = signedIn();

        givenTheRightPassword(user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(signInService.signIn(user, true)).thenReturn(signedIn);

        assertThat(authService.login(loginDto, request)).isSameAs(signedIn);
        verify(signInService, never()).requireSecondStep(any(), anyBoolean());
        assertThat(output).contains("Legacy two-factor login user id=1").doesNotContain("123456");
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void aKeysOnlyAccountOnTheLegacyPathSignsInWithARecoveryCode() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("ABCD-EF23").build();
        final var user = keysOnlyUser();
        final var signedIn = signedIn();

        givenTheRightPassword(user);
        when(twoFactorService.verifyLoginCode(user, "ABCD-EF23")).thenReturn(true);
        when(signInService.signIn(user, false)).thenReturn(signedIn);

        assertThat(authService.login(loginDto, request)).isSameAs(signedIn);
        verify(loginAttemptService).loginSucceeded("2fa:1");
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void aKeysOnlyAccountOnTheLegacyPathIsRefusedASixDigitCode() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("123456").build();
        final var user = keysOnlyUser();

        givenTheRightPassword(user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Invalid two-factor code");
        verify(loginAttemptService).loginFailed("2fa:1");
        verify(loginAttemptService).loginFailed("127.0.0.1");
        verifyNoInteractions(signInService);
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void shouldFailLoginWhenTwoFactorCodeInvalid() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("000000").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(true).twoStepEnabled(true).build();

        givenTheRightPassword(user);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Invalid two-factor code");
        verify(loginAttemptService).loginFailed("2fa:1");
        verify(loginAttemptService).loginFailed("127.0.0.1");
        verifyNoInteractions(signInService);
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void stopsCheckingTwoFactorCodesForAnAccountAfterTooManyWrongOnes() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("111111").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(true).twoStepEnabled(true).build();

        givenTheRightPassword(user);
        when(loginAttemptService.isBlocked("2fa:1")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Too many invalid two-factor codes. Try again later.");
        verify(twoFactorService, never()).verifyLoginCode(any(), any());
        verifyNoInteractions(signInService);
    }

    @Test
    void shouldVerifyEmail() {
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(false).build();
        final var token = cc.hrva.urlshortener.model.VerificationToken.builder().id(1L).user(user).active(true).build();

        when(verificationTokenService.validateToken("tok")).thenReturn(token);

        authService.verifyEmail("tok");

        assertThat(user.getEmailVerified()).isTrue();
        verify(userService).persistUser(user);
        verify(verificationTokenService).deactivateAndSaveToken(token);
        verify(sendingEmailService).sendWelcomeEmail(user);
    }

    @Test
    void shouldResendVerificationForUnverifiedUser() {
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(false).build();
        final var token = cc.hrva.urlshortener.model.VerificationToken.builder().id(1L).user(user).build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        when(verificationTokenService.createTokenForUser(user)).thenReturn(token);

        authService.resendVerificationEmail("test@example.com");

        verify(verificationTokenService).deactivateActiveTokensForUser(user);
        verify(sendingEmailService).sendVerificationEmail(user, token);
    }

    @Test
    void shouldNotResendForAlreadyVerifiedUser() {
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        authService.resendVerificationEmail("test@example.com");

        verify(verificationTokenService, org.mockito.Mockito.never()).createTokenForUser(any());
    }

    @Test
    void shouldNotResendForUnknownEmail() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(java.util.Optional.empty());

        authService.resendVerificationEmail("missing@example.com");

        verify(verificationTokenService, org.mockito.Mockito.never()).createTokenForUser(any());
    }

    private void givenTheRightPassword(final User user) {
        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(userService.fetchUserFromEmail(user.getEmail())).thenReturn(user);
    }

    private static User keysOnlyUser() {
        return User.builder().id(1L).email("test@example.com").emailVerified(true)
                .twoFactorEnabled(false).twoFactorSecret(null).twoStepEnabled(true).build();
    }

    private static ResponseEntity<JWTTokenDto> signedIn() {
        return ResponseEntity.ok(JWTTokenDto.builder().token("Bearer jwtToken").build());
    }

}
