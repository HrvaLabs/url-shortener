package cc.hrva.urlshortener.security;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    private static final String PENDING_TOKEN = "Ab-_cD3fGh1jKlMnOpQrStUvWxYz0123456789-_AbC";

    private OAuth2LoginSuccessHandler handler;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthoritiesService authoritiesService;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private AppProperties appProperties;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Authentication authentication;

    @Mock
    private OAuth2User oAuth2User;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PendingLoginStore pendingLoginStore;

    @Mock
    private TwoStepMethods twoStepMethods;

    @BeforeEach
    void setUp() {
        this.handler = new OAuth2LoginSuccessHandler(userRepository, authoritiesService, tokenProvider,
                appProperties, Validation.buildDefaultValidatorFactory().getValidator(), eventPublisher,
                pendingLoginStore, twoStepMethods);
        lenient().when(authentication.getPrincipal()).thenReturn(oAuth2User);
        lenient().when(appProperties.getFrontendUrl()).thenReturn("http://fe");
    }

    @Test
    void shouldRedirectWithErrorWhenNoEmail() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=no_email");
        verify(tokenProvider, never()).createToken(any());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldBlockInactiveAccount(final CapturedOutput output) throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("evil@example.com");
        when(userRepository.findByEmail("evil@example.com"))
                .thenReturn(Optional.of(User.builder().id(1L).email("evil@example.com").active(false).build()));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=account_inactive");
        verify(tokenProvider, never()).createToken(any());
        assertThat(output).contains("OAuth2 login blocked: inactive account email=e***@example.com").doesNotContain("evil@example.com");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLoginExistingActiveUserAndSetLastLogin(final CapturedOutput output) throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(userRepository).save(argThat(saved -> saved.getLastLogin() != null));
        verify(response).sendRedirect(contains("http://fe/auth/callback?token=jwt-token"));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verifyNoInteractions(pendingLoginStore, twoStepMethods);
        assertThat(output).contains("OAuth2 login success email=o***@example.com").doesNotContain("ok@example.com");
    }

    @ParameterizedTest
    @MethodSource("emailsTheAccountCouldNotStore")
    void refusesAProviderEmailTheAccountCouldNotStoreBeforeCreatingAnything(final String email) throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn(email);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=invalid_email");
        verify(userRepository, never()).save(any());
        verify(tokenProvider, never()).createToken(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldCreateNewUserAndNotifyAdmin(final CapturedOutput output) throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("new@example.com");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(authoritiesService.getDefaultAuthority())
                .thenReturn(Authorities.builder().id(1L).name("ROLE_USER").build());
        when(appProperties.getUserApiKeySlots()).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof UserRegisteredEvent registered
                && "new@example.com".equals(registered.user().getEmail()) && registered.verificationToken() == null));
        verify(response).sendRedirect(contains("token=jwt-token"));
        assertThat(output).contains("Created OAuth2 user email=n***@example.com", "OAuth2 login success email=n***@example.com")
                .doesNotContain("new@example.com");
    }

    @Test
    void aNewAccountAlertsTheAdminWithItsIdAndProviderButNotItsEmail() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("new@example.com");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(authoritiesService.getDefaultAuthority())
                .thenReturn(Authorities.builder().id(1L).name("ROLE_USER").build());
        when(appProperties.getUserApiKeySlots()).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(invocation -> {
            final User saved = invocation.getArgument(0);
            saved.setId(31L);
            return saved;
        });
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(eventPublisher).publishEvent(new AdminAlertEvent.UserSignedUp(31L, "google"));
    }

    @Test
    void aGitHubSignUpIsStoredAndAlertedAsGitHub() throws Exception {
        final var gitHub = new OAuth2AuthenticationToken(oAuth2User, List.of(), "github");
        when(oAuth2User.getAttribute("email")).thenReturn("octo@example.com");
        when(userRepository.findByEmail("octo@example.com")).thenReturn(Optional.empty());
        when(authoritiesService.getDefaultAuthority())
                .thenReturn(Authorities.builder().id(1L).name("ROLE_USER").build());
        when(appProperties.getUserApiKeySlots()).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(invocation -> {
            final User saved = invocation.getArgument(0);
            saved.setId(32L);
            return saved;
        });
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, gitHub);

        verify(userRepository, atLeastOnce()).save(argThat(saved -> "github".equals(saved.getAuthProvider())));
        verify(eventPublisher).publishEvent(new AdminAlertEvent.UserSignedUp(32L, "github"));
    }

    @Test
    void aGoogleSignUpIsStoredAsGoogle() throws Exception {
        final var google = new OAuth2AuthenticationToken(oAuth2User, List.of(), "google");
        when(oAuth2User.getAttribute("email")).thenReturn("new@example.com");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(authoritiesService.getDefaultAuthority())
                .thenReturn(Authorities.builder().id(1L).name("ROLE_USER").build());
        when(appProperties.getUserApiKeySlots()).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, google);

        verify(userRepository, atLeastOnce()).save(argThat(saved -> "google".equals(saved.getAuthProvider())));
    }

    @Test
    void aTwoStepAccountGetsAPendingTokenInsteadOfAJwt() throws Exception {
        final var user = twoStepUser(7L, "two@example.com", "google");
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("two@example.com");
        when(userRepository.findByEmail("two@example.com")).thenReturn(Optional.of(user));
        when(pendingLoginStore.create(7L, false)).thenReturn(PENDING_TOKEN);
        when(twoStepMethods.of(user)).thenReturn(List.of(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE));

        handler.onAuthenticationSuccess(request, response, authentication);

        final var redirect = redirectSent();
        assertThat(redirect)
                .startsWith("http://localhost:3000/auth/callback?pendingToken=")
                .contains("&methods=TOTP,RECOVERY_CODE")
                .doesNotContain("token=")
                .isEqualTo("http://localhost:3000/auth/callback?pendingToken=" + PENDING_TOKEN
                        + "&methods=TOTP,RECOVERY_CODE");
        verify(pendingLoginStore).create(7L, false);
        verifyNoInteractions(tokenProvider);
        verify(userRepository, never()).save(any());
        assertThat(user.getLastLogin()).isNull();
    }

    @Test
    void aGoogleSignInToAPasswordAccountWithTwoStepNeedsTheSecondStep() throws Exception {
        final var google = new OAuth2AuthenticationToken(oAuth2User, List.of(), "google");
        final var user = twoStepUser(8L, "local@example.com", "local");
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("local@example.com");
        when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(user));
        when(pendingLoginStore.create(8L, false)).thenReturn(PENDING_TOKEN);
        when(twoStepMethods.of(user))
                .thenReturn(List.of(TwoStepMethod.TOTP, TwoStepMethod.WEBAUTHN, TwoStepMethod.RECOVERY_CODE));

        handler.onAuthenticationSuccess(request, response, google);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?pendingToken=" + PENDING_TOKEN
                + "&methods=TOTP,WEBAUTHN,RECOVERY_CODE");
        verifyNoInteractions(tokenProvider);
        verify(userRepository, never()).save(any());
        assertThat(user.getAuthProvider()).isEqualTo("local");
        assertThat(user.getLastLogin()).isNull();
    }

    @Test
    void redisDownSendsTheCallbackAnError() throws Exception {
        final var user = twoStepUser(9L, "two@example.com", "github");
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("two@example.com");
        when(userRepository.findByEmail("two@example.com")).thenReturn(Optional.of(user));
        when(twoStepMethods.of(user)).thenReturn(List.of(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE));
        when(pendingLoginStore.create(9L, false))
                .thenThrow(new SignInUnavailableException(PendingLoginStore.UNAVAILABLE, new IllegalStateException()));

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?error=two_step_unavailable");
        final var order = inOrder(twoStepMethods, pendingLoginStore);
        order.verify(twoStepMethods).of(user);
        order.verify(pendingLoginStore).create(9L, false);
        verifyNoInteractions(tokenProvider);
        verify(userRepository, never()).save(any());
    }

    @Test
    void aFailedMethodLookupLeavesNoPendingToken() {
        final var user = twoStepUser(11L, "two@example.com", "google");
        when(oAuth2User.getAttribute("email")).thenReturn("two@example.com");
        when(userRepository.findByEmail("two@example.com")).thenReturn(Optional.of(user));
        when(twoStepMethods.of(user)).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> handler.onAuthenticationSuccess(request, response, authentication))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(pendingLoginStore, tokenProvider);
        verify(userRepository, never()).save(any());
    }

    @Test
    void anInactiveTwoStepAccountIsRefusedBeforeTheSecondStep() throws Exception {
        final var user = twoStepUser(10L, "gone@example.com", "google");
        user.setActive(false);
        when(oAuth2User.getAttribute("email")).thenReturn("gone@example.com");
        when(userRepository.findByEmail("gone@example.com")).thenReturn(Optional.of(user));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=account_inactive");
        verifyNoInteractions(tokenProvider, pendingLoginStore, twoStepMethods);
        verify(userRepository, never()).save(any());
    }

    private String redirectSent() throws Exception {
        final var redirect = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(redirect.capture());

        return redirect.getValue();
    }

    private static User twoStepUser(final Long id, final String email, final String authProvider) {
        return User.builder().id(id).email(email).active(true).authProvider(authProvider)
                .twoFactorEnabled(true).twoStepEnabled(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
    }

    private static Stream<Named<String>> emailsTheAccountCouldNotStore() {
        return Stream.of(
                Named.of("a 65-character local part", "a".repeat(65) + "@example.com"),
                Named.of("a valid 268-character address", "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "example.com"));
    }
}
