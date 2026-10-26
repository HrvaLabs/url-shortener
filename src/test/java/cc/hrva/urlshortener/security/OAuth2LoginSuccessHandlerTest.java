package cc.hrva.urlshortener.security;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

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

    @BeforeEach
    void setUp() {
        this.handler = new OAuth2LoginSuccessHandler(userRepository, authoritiesService, tokenProvider,
                appProperties, Validation.buildDefaultValidatorFactory().getValidator(), eventPublisher);
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
    void shouldBlockInactiveAccount() throws Exception {
        when(oAuth2User.getAttribute("email")).thenReturn("evil@example.com");
        when(userRepository.findByEmail("evil@example.com"))
                .thenReturn(Optional.of(User.builder().id(1L).email("evil@example.com").active(false).build()));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://fe/auth/callback?error=account_inactive");
        verify(tokenProvider, never()).createToken(any());
    }

    @Test
    void shouldLoginExistingActiveUserAndSetLastLogin() throws Exception {
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
    void shouldCreateNewUserAndNotifyAdmin() throws Exception {
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

    private static Stream<Named<String>> emailsTheAccountCouldNotStore() {
        return Stream.of(
                Named.of("a 65-character local part", "a".repeat(65) + "@example.com"),
                Named.of("a valid 268-character address", "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "example.com"));
    }
}
