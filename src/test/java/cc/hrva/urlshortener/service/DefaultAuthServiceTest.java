package cc.hrva.urlshortener.service;

import jakarta.servlet.http.HttpServletRequest;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.converter.UserRegisterDtoToUserConverter;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.dto.LoginDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserRegisterDto;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.impl.DefaultAuthService;
import cc.hrva.urlshortener.validator.UserValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAuthServiceTest {

    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private UserValidator userValidator;

    @Mock
    private TokenProvider tokenProvider;

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
    private UserToUserDtoConverter userToUserDtoConverter;

    @Mock
    private AuthenticationManagerBuilder authenticationManagerBuilder;

    @Mock
    private UserRegisterDtoToUserConverter userRegisterDtoToUserConverter;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private AppProperties appProperties;

    @Mock
    private HttpServletRequest request;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        this.authService = new DefaultAuthService(userService, userValidator, tokenProvider, userRepository,
                clientIpResolver, loginAttemptService, sendingEmailService, twoFactorService, userToUserDtoConverter,
                verificationTokenService, authenticationManagerBuilder, userRegisterDtoToUserConverter, appProperties);
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
    void shouldLoginSuccessfully() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).build();
        final var userDto = UserDto.builder().id(1L).email("test@example.com").build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36000L);
        when(tokenProvider.createToken(authentication, 36000L)).thenReturn("jwtToken");
        when(userService.fetchUserFromEmail("test@example.com")).thenReturn(user);
        when(userToUserDtoConverter.convert(user)).thenReturn(userDto);

        final var response = authService.login(loginDto, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getToken()).isEqualTo("Bearer jwtToken");
        assertThat(response.getBody().getUser()).isEqualTo(userDto);
        assertThat(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER)).isEqualTo("Bearer jwtToken");
        verify(userService).userHasLoggedIn(user);
    }

    @Test
    void shouldThrowWhenBlocked() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Request has been blocked");
    }

    @Test
    void shouldBlockLoginWhenEmailNotVerified() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(false).build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36000L);
        when(tokenProvider.createToken(authentication, 36000L)).thenReturn("jwtToken");
        when(userService.fetchUserFromEmail("test@example.com")).thenReturn(user);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(cc.hrva.urlshortener.exception.EmailNotVerifiedException.class);
        verify(userService, org.mockito.Mockito.never()).userHasLoggedIn(any());
    }

    @Test
    void shouldReturnTwoFactorRequiredWhenCodeMissing() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).twoFactorEnabled(true).build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36000L);
        when(tokenProvider.createToken(authentication, 36000L)).thenReturn("jwtToken");
        when(userService.fetchUserFromEmail("test@example.com")).thenReturn(user);

        final var response = authService.login(loginDto, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTwoFactorRequired()).isTrue();
        assertThat(response.getBody().getToken()).isNull();
        verify(userService, org.mockito.Mockito.never()).userHasLoggedIn(any());
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
    void shouldLoginWhenTwoFactorCodeValid() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("123456").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).twoFactorEnabled(true).build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36000L);
        when(tokenProvider.createToken(authentication, 36000L)).thenReturn("jwtToken");
        when(userService.fetchUserFromEmail("test@example.com")).thenReturn(user);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);
        when(userToUserDtoConverter.convert(user)).thenReturn(UserDto.builder().id(1L).build());

        final var response = authService.login(loginDto, request);

        assertThat(response.getBody().getToken()).isEqualTo("Bearer jwtToken");
        verify(userService).userHasLoggedIn(user);
        verify(loginAttemptService).loginSucceeded("2fa:1");
    }

    @Test
    void shouldFailLoginWhenTwoFactorCodeInvalid() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("000000").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).twoFactorEnabled(true).build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36000L);
        when(tokenProvider.createToken(authentication, 36000L)).thenReturn("jwtToken");
        when(userService.fetchUserFromEmail("test@example.com")).thenReturn(user);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class);
        verify(loginAttemptService).loginFailed("2fa:1");
        verify(loginAttemptService).loginFailed("127.0.0.1");
    }

    @Test
    void stopsCheckingTwoFactorCodesForAnAccountAfterTooManyWrongOnes() {
        final var loginDto = LoginDto.builder().email("test@example.com").password("password123").code("111111").build();
        final var user = User.builder().id(1L).email("test@example.com").emailVerified(true).twoFactorEnabled(true).build();

        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("127.0.0.1")).thenReturn(false);
        when(loginAttemptService.isBlocked("2fa:1")).thenReturn(true);
        when(authenticationManagerBuilder.getObject()).thenReturn(authenticationManager);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36000L);
        when(tokenProvider.createToken(authentication, 36000L)).thenReturn("jwtToken");
        when(userService.fetchUserFromEmail("test@example.com")).thenReturn(user);

        assertThatThrownBy(() -> authService.login(loginDto, request))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Too many invalid two-factor codes. Try again later.");
        verify(twoFactorService, org.mockito.Mockito.never()).verifyLoginCode(any(), any());
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

}
