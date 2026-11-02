package cc.hrva.urlshortener.security;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.signin.OAuthSignInCodes;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private OAuth2LoginSuccessHandler handler;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthoritiesService authoritiesService;

    @Mock
    private OAuthSignInCodes signInCodes;

    @Mock
    private AppProperties appProperties;

    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

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
    private SignInMethodService signInMethodService;

    @BeforeEach
    void setUp() {
        this.handler = new OAuth2LoginSuccessHandler(userRepository, authoritiesService, signInCodes,
                appProperties, Validation.buildDefaultValidatorFactory().getValidator(), eventPublisher,
                signInMethodService, settingsService);
        lenient().when(authentication.getPrincipal()).thenReturn(oAuth2User);
        lenient().when(appProperties.getFrontendUrl()).thenReturn("http://fe");
        lenient().when(oAuth2User.getAttribute("email_verified")).thenReturn(true);
        lenient().when(request.getAttribute(OAuthChallenges.ATTRIBUTE)).thenReturn(CHALLENGE);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aSignInStartedWithoutAChallengeIsSentBackBeforeAnythingIsLookedUp(final CapturedOutput output) throws Exception {
        when(request.getAttribute(OAuthChallenges.ATTRIBUTE)).thenReturn(null);
        lenient().when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=provider_error");
        verifyNoInteractions(userRepository, signInCodes, signInMethodService);
        assertThat(output).contains("OAuth2 login refused: the sign-in was started without a challenge");
    }

    @Test
    void shouldRedirectWithErrorWhenNoEmail() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=no_email");
        verify(signInCodes, never()).create(any(), any(), any());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldBlockInactiveAccount(final CapturedOutput output) throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("evil@example.com");
        when(userRepository.findByEmail("evil@example.com"))
                .thenReturn(Optional.of(User.builder().id(1L).email("evil@example.com").active(false).build()));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=account_inactive");
        verify(signInCodes, never()).create(any(), any(), any());
        verifyNoInteractions(signInMethodService);
        assertThat(output).contains("OAuth2 login blocked: inactive account email=e***@example.com user id=1").doesNotContain("evil@example.com");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLoginExistingActiveUserAndSetLastLogin(final CapturedOutput output) throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(userRepository).recordLastLogin(eq(1L), argThat(signedInAt -> signedInAt != null));
        verify(userRepository, never()).save(any());
        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        assertThat(output).contains("OAuth2 login success email=o***@example.com user id=1").doesNotContain("ok@example.com");
    }

    @ParameterizedTest
    @CsvSource({"google, GOOGLE", "github, GITHUB"})
    void aSignInRecordsItsProviderAtTheTimeOfTheLastLogin(final String provider, final SignInMethod method) throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true).emailVerified(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        final var lastLogin = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).recordLastLogin(eq(1L), lastLogin.capture());
        verify(signInMethodService).record(1L, method, lastLogin.getValue());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anUnrecognisedProviderSignsInWithoutRecordingAMethod(final CapturedOutput output) throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true).emailVerified(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("facebook"));

        verify(userRepository).recordLastLogin(eq(1L), any());
        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
        verifyNoInteractions(signInMethodService);
        assertThat(output).contains("OAuth2 sign-in method not recorded, unrecognised provider=facebook user id=1").doesNotContain("ok@example.com");
    }

    @Test
    void aNewAccountIsRecordedWithItsProvider() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("octo@example.com");
        when(userRepository.findByEmail("octo@example.com")).thenReturn(Optional.empty());
        when(authoritiesService.getDefaultAuthority()).thenReturn(Authorities.builder().id(1L).name("ROLE_USER").build());
        when(userRepository.save(any())).thenAnswer(invocation -> {
            final User saved = invocation.getArgument(0);
            saved.setId(33L);
            return saved;
        });
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("github"));

        verify(signInMethodService).record(eq(33L), eq(SignInMethod.GITHUB), any());
    }

    @ParameterizedTest
    @MethodSource("emailsTheAccountCouldNotStore")
    void refusesAProviderEmailTheAccountCouldNotStoreBeforeCreatingAnything(final String email) throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn(email);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=invalid_email");
        verify(userRepository, never()).save(any());
        verify(signInCodes, never()).create(any(), any(), any());
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
        when(userRepository.save(any())).thenAnswer(invocation -> {
            final User saved = invocation.getArgument(0);
            saved.setId(31L);
            return saved;
        });
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof UserRegisteredEvent registered
                && "new@example.com".equals(registered.user().getEmail()) && registered.verificationToken() == null));
        verify(response).sendRedirect(contains("code=one-time-code"));
        assertThat(output).contains("Created OAuth2 user email=n***@example.com user id=31", "OAuth2 login success email=n***@example.com user id=31")
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
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

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
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

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
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, google);

        verify(userRepository, atLeastOnce()).save(argThat(saved -> "google".equals(saved.getAuthProvider())));
    }

    @Test
    void aTwoStepAccountGetsOnlyAOneTimeCodeAndNoSignInIsRecordedYet() throws Exception {
        final var user = twoStepUser(7L, "two@example.com", "google");
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("two@example.com");
        when(userRepository.findByEmail("two@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(7L, SignInMethod.GOOGLE, CHALLENGE)).thenReturn("Cd3_one-time");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?code=Cd3_one-time");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).recordLastLogin(any(), any());
        verify(signInMethodService, never()).record(any(), any(), any());
        assertThat(user.getLastLogin()).isNull();
    }

    @Test
    void aGoogleSignInToAPasswordAccountWithTwoStepGetsOnlyAOneTimeCode() throws Exception {
        final var google = new OAuth2AuthenticationToken(oAuth2User, List.of(), "google");
        final var user = twoStepUser(8L, "local@example.com", "local");
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("local@example.com");
        when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(8L, SignInMethod.GOOGLE, CHALLENGE)).thenReturn("Cd3_two-step");

        handler.onAuthenticationSuccess(request, response, google);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?code=Cd3_two-step");
        verify(userRepository, never()).save(any());
        assertThat(user.getAuthProvider()).isEqualTo("local");
        assertThat(user.getLastLogin()).isNull();
    }

    @Test
    void theCallbackCarriesAOneTimeCodeAndNeverTheSignInToken() throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(1L, SignInMethod.GOOGLE, CHALLENGE)).thenReturn("Cd3_one-time");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?code=Cd3_one-time");
    }

    @Test
    void redisDownForTheCodeSendsTheCallbackAnErrorAndRecordsNoSignIn() throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenThrow(new SignInUnavailableException(OAuthSignInCodes.UNAVAILABLE, new IllegalStateException()));

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?error=sign_in_unavailable");
        verify(userRepository, never()).recordLastLogin(any(), any());
        verify(signInMethodService, never()).record(any(), any(), any());
    }

    @Test
    void redisDownForATwoStepAccountsCodeSendsTheCallbackAnError() throws Exception {
        final var user = twoStepUser(9L, "two@example.com", "github");
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("two@example.com");
        when(userRepository.findByEmail("two@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenThrow(new SignInUnavailableException(OAuthSignInCodes.UNAVAILABLE, new IllegalStateException()));

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?error=sign_in_unavailable");
        verify(userRepository, never()).save(any());
    }

    @Test
    void everyRedirectToTheCallbackIsNeitherStoredNorSentAsAReferrer() throws Exception {
        final var user = twoStepUser(12L, "two@example.com", "google");
        when(oAuth2User.getAttribute("email")).thenReturn("two@example.com");
        when(userRepository.findByEmail("two@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).setHeader("Referrer-Policy", "no-referrer");
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).sendRedirect("http://fe/auth/callback?code=one-time-code");
    }

    @Test
    void anErrorRedirectToTheCallbackIsNeitherStoredNorSentAsAReferrer() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).setHeader("Referrer-Policy", "no-referrer");
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).sendRedirect("http://fe/auth/callback?error=no_email");
    }

    @Test
    void anInactiveTwoStepAccountIsRefusedBeforeTheSecondStep() throws Exception {
        final var user = twoStepUser(10L, "gone@example.com", "google");
        user.setActive(false);
        when(oAuth2User.getAttribute("email")).thenReturn("gone@example.com");
        when(userRepository.findByEmail("gone@example.com")).thenReturn(Optional.of(user));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=account_inactive");
        verifyNoInteractions(signInCodes);
        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"google, google_email_unverified", "github, github_email_unverified"})
    void anEmailTheProviderHasNotVerifiedNeverReachesAnAccount(final String provider, final String error) throws Exception {
        when(oAuth2User.getAttribute("email_verified")).thenReturn(false);
        lenient().when(oAuth2User.getAttribute("email")).thenReturn("taken@example.com");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(response).sendRedirect("http://fe/auth/callback?error=" + error);
        verifyNoInteractions(userRepository, signInCodes, eventPublisher);
    }

    @ParameterizedTest
    @CsvSource({"google, google_email_unverified", "github, github_email_unverified"})
    void anAccountWithoutTheVerifiedFlagIsRefused(final String provider, final String error) throws Exception {
        when(oAuth2User.getAttribute("email_verified")).thenReturn(null);
        lenient().when(oAuth2User.getAttribute("email")).thenReturn("taken@example.com");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(response).sendRedirect("http://fe/auth/callback?error=" + error);
        verifyNoInteractions(userRepository, signInCodes, eventPublisher);
    }

    @Test
    void aVerifiedFlagSentAsTextStillSignsIn() throws Exception {
        final var user = User.builder().id(1L).email("ok@example.com").active(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email_verified")).thenReturn("true");
        when(oAuth2User.getAttribute("email")).thenReturn("ok@example.com");
        when(userRepository.findByEmail("ok@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("google"));

        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "github"})
    void anOAuthSignUpFollowsNewSignups(final String provider) throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.NEW_SIGNUPS);
        when(oAuth2User.getAttribute("email")).thenReturn("new@example.com");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(response).sendRedirect("http://fe/auth/callback?error=registration_disabled");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).recordLastLogin(any(), any());
        verifyNoInteractions(signInCodes, eventPublisher, authoritiesService, signInMethodService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "github"})
    void pausedSignUpsStillLetAnExistingAccountSignIn(final String provider) throws Exception {
        final var user = User.builder().id(5L).email("member@example.com").active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.NEW_SIGNUPS);
        when(oAuth2User.getAttribute("email")).thenReturn("member@example.com");
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
        verify(userRepository).recordLastLogin(eq(5L), any());
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "github"})
    void anAccountWhoseEmailWasNeverConfirmedLosesItsPasswordBeforeItCountsAsConfirmed(final String provider) throws Exception {
        final var user = User.builder().id(12L).email("claimed@example.com").password("$2a$10$preRegisteredPasswordHash")
                .emailVerified(false).active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("claimed@example.com");
        when(userRepository.findByEmail("claimed@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        final var order = inOrder(signInCodes, userRepository);
        order.verify(userRepository).confirmEmailAndReplacePassword(eq(12L), argThat(password -> password != null
                && password.length() >= 32 && !password.startsWith("$2") && !password.equals(user.getPassword())));
        order.verify(signInCodes).create(any(), any(), any());
        order.verify(userRepository).recordLastLogin(eq(12L), any());
        verify(userRepository, never()).save(any());
        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theReplacedPasswordIsLoggedOnlyWhenTheRowWasStillUnconfirmed(final CapturedOutput output) throws Exception {
        final var user = User.builder().id(16L).email("raced@example.com").password("$2a$10$hash").emailVerified(false).active(true)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("raced@example.com");
        when(userRepository.findByEmail("raced@example.com")).thenReturn(Optional.of(user));
        when(userRepository.confirmEmailAndReplacePassword(eq(16L), any())).thenReturn(0, 1);
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("google"));
        assertThat(output).doesNotContain("replaced its unconfirmed password");

        handler.onAuthenticationSuccess(request, response, signIn("google"));
        assertThat(output).contains("OAuth2 login confirmed the account's email and replaced its unconfirmed password user id=16");
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "github"})
    void aConfirmedAccountKeepsItsPassword(final String provider) throws Exception {
        final var user = User.builder().id(13L).email("member@example.com").password("$2a$10$memberPasswordHash")
                .emailVerified(true).active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("member@example.com");
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(userRepository, never()).confirmEmailAndReplacePassword(any(), any());
        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
    }

    @Test
    void anInactiveUnconfirmedAccountIsRefusedWithoutBeingTouched() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("gone@example.com");
        when(userRepository.findByEmail("gone@example.com")).thenReturn(Optional.of(
                User.builder().id(14L).email("gone@example.com").password("$2a$10$hash").emailVerified(false).active(false).build()));

        handler.onAuthenticationSuccess(request, response, signIn("github"));

        verify(response).sendRedirect("http://fe/auth/callback?error=account_inactive");
        verify(userRepository, never()).confirmEmailAndReplacePassword(any(), any());
    }

    @Test
    void aGitHubSignInToATwoStepAccountGetsOnlyAOneTimeCode() throws Exception {
        final var user = twoStepUser(15L, "octo@example.com", "github");
        user.setEmailVerified(true);
        when(appProperties.getFrontendUrl()).thenReturn("http://localhost:3000");
        when(oAuth2User.getAttribute("email")).thenReturn("octo@example.com");
        when(userRepository.findByEmail("octo@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(15L, SignInMethod.GITHUB, CHALLENGE)).thenReturn("Cd3_octo");

        handler.onAuthenticationSuccess(request, response, signIn("github"));

        assertThat(redirectSent()).isEqualTo("http://localhost:3000/auth/callback?code=Cd3_octo");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).recordLastLogin(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"google, GOOGLE", "github, GITHUB"})
    void aTurnedOffProviderIsRefusedBeforeAnythingIsWritten(final String provider, final SignInMethod method) throws Exception {
        final var user = User.builder().id(17L).email("off@example.com").password("$2a$10$preRegisteredPasswordHash")
                .emailVerified(false).active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("off@example.com");
        when(userRepository.findByEmail("off@example.com")).thenReturn(Optional.of(user));
        when(signInMethodService.isTurnedOff(17L, method)).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(response).sendRedirect("http://fe/auth/callback?error=provider_turned_off&provider=" + method.name());
        verify(userRepository, never()).confirmEmailAndReplacePassword(any(), any());
        verify(userRepository, never()).recordLastLogin(any(), any());
        verify(userRepository, never()).save(any());
        verify(signInMethodService, never()).record(any(), any(), any());
        verifyNoInteractions(signInCodes, eventPublisher);
    }

    @Test
    void aTurnedOffProviderIsRefusedBeforeTheSecondStep() throws Exception {
        final var user = twoStepUser(18L, "two-off@example.com", "github");
        when(oAuth2User.getAttribute("email")).thenReturn("two-off@example.com");
        when(userRepository.findByEmail("two-off@example.com")).thenReturn(Optional.of(user));
        when(signInMethodService.isTurnedOff(18L, SignInMethod.GITHUB)).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, signIn("github"));

        verify(response).sendRedirect("http://fe/auth/callback?error=provider_turned_off&provider=GITHUB");
        verifyNoInteractions(signInCodes);
    }

    @Test
    void anotherProviderTurnedOffDoesNotStopThisOne() throws Exception {
        final var user = User.builder().id(19L).email("both@example.com").password("$2a$10$memberPasswordHash")
                .emailVerified(true).active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(oAuth2User.getAttribute("email")).thenReturn("both@example.com");
        when(userRepository.findByEmail("both@example.com")).thenReturn(Optional.of(user));
        lenient().when(signInMethodService.isTurnedOff(19L, SignInMethod.GITHUB)).thenReturn(true);
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("google"));

        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
        verify(signInMethodService).record(eq(19L), eq(SignInMethod.GOOGLE), any());
    }

    @ParameterizedTest
    @CsvSource({"google, GOOGLE_SIGN_IN, GOOGLE", "github, GITHUB_SIGN_IN, GITHUB"})
    @ExtendWith(OutputCaptureExtension.class)
    void aProviderSwitchedOffIsRefusedBeforeAnything(final String provider, final SettingKey key, final SignInMethod method,
            final CapturedOutput output) throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, key);
        lenient().when(oAuth2User.getAttribute("email_verified")).thenReturn(false);
        lenient().when(oAuth2User.getAttribute("email")).thenReturn("member@example.com");

        handler.onAuthenticationSuccess(request, response, signIn(provider));

        verify(response).sendRedirect("http://fe/auth/callback?error=provider_disabled&provider=" + method.name());
        verifyNoInteractions(userRepository, signInMethodService, signInCodes, eventPublisher, authoritiesService);
        assertThat(output).contains("OAuth2 login refused: this provider is turned off on hrva.cc provider=" + provider)
                .doesNotContain("member@example.com");
    }

    @Test
    void aProviderSwitchedOffIsRefusedBeforeTheAccountsOwnTurnOff() throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.GITHUB_SIGN_IN);
        lenient().when(signInMethodService.isTurnedOff(any(), any())).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, signIn("github"));

        verify(response).sendRedirect("http://fe/auth/callback?error=provider_disabled&provider=GITHUB");
        verifyNoInteractions(userRepository, signInMethodService);
    }

    @Test
    void theOtherProviderSwitchedOffDoesNotStopThisOne() throws Exception {
        final var user = User.builder().id(20L).email("member@example.com").emailVerified(true).active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.GITHUB_SIGN_IN);
        when(oAuth2User.getAttribute("email")).thenReturn("member@example.com");
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("google"));

        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
        verify(signInMethodService).record(eq(20L), eq(SignInMethod.GOOGLE), any());
    }

    @Test
    void anUnrecognisedProviderIsNotGatedByTheProviderSwitches() throws Exception {
        final var user = User.builder().id(21L).email("member@example.com").emailVerified(true).active(true).authProvider("local")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.GOOGLE_SIGN_IN);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.GITHUB_SIGN_IN);
        when(oAuth2User.getAttribute("email")).thenReturn("member@example.com");
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(signInCodes.create(any(), any(), any())).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, signIn("gitlab"));

        verify(response).sendRedirect(contains("http://fe/auth/callback?code=one-time-code"));
    }

    @Test
    void aSwitchedOnProviderWithSignUpsPausedStillRefusesANewAccountAsRegistrationDisabled() throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.NEW_SIGNUPS);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.GITHUB_SIGN_IN);
        when(oAuth2User.getAttribute("email")).thenReturn("new@example.com");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        handler.onAuthenticationSuccess(request, response, signIn("google"));

        verify(response).sendRedirect("http://fe/auth/callback?error=registration_disabled");
        verify(userRepository, never()).save(any());
    }

    private OAuth2AuthenticationToken signIn(final String provider) {
        return new OAuth2AuthenticationToken(oAuth2User, List.of(), provider);
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
