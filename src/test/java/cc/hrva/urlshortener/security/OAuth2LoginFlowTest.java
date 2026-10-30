package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.OAuth2ClientConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = OAuth2LoginFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.github-sign-in.client-id=test-github-client",
                "app.github-sign-in.client-secret=test-github-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost",
                "app.mcp.signing-key=not-used-by-this-test"
        })
@AutoConfigureMockMvc
class OAuth2LoginFlowTest {

    private static final String ACCESS_TOKEN = "gho_flow-test-token";
    private static final String CALLBACK = "https://hrva.cc:443/login/oauth2/code/";
    private static final String PROFILE = """
            {"id": 583231, "login": "octocat", "name": "The Octocat", "email": null}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockRestServiceServer providers;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private AuthoritiesService authoritiesService;

    @MockitoBean
    private PendingLoginStore pendingLoginStore;

    @MockitoBean
    private TwoStepMethods twoStepMethods;

    @MockitoBean
    private SignInMethodService signInMethodService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @AfterEach
    void forgetTheExpectedCalls() {
        providers.reset();
    }

    @Test
    void aGitHubSignInAsksGitHubForTheEmailScopeAndSignsInWithTheVerifiedPrimaryEmail() throws Exception {
        final var member = User.builder().id(7L).email("octocat@example.com").active(true).authProvider("github")
                .authorities(List.of(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(userRepository.findByEmail("octocat@example.com")).thenReturn(Optional.of(member));
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");
        expectProfileAndEmails("""
                [{"email": "octocat@example.com", "primary": true, "verified": true, "visibility": null}]""");

        returnFrom("github", authorizeAtGitHub())
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/auth/callback?token=jwt-token"));

        providers.verify();
        verify(userRepository).recordLastLogin(eq(7L), any());
    }

    @Test
    void aGitHubAccountWithoutAVerifiedPrimaryEmailIsSentBackWithTheReason() throws Exception {
        expectProfileAndEmails("""
                [{"email": "octocat@example.com", "primary": true, "verified": false, "visibility": null}]""");

        returnFrom("github", authorizeAtGitHub())
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/auth/callback?error=github_email_unverified"));

        providers.verify();
        verify(userRepository, never()).findByEmail(anyString());
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void aGitHubErrorSendsTheSignInBackToTheSite() throws Exception {
        providers.expect(requestTo("https://api.github.com/user"))
                .andRespond(withSuccess(PROFILE, MediaType.APPLICATION_JSON));
        providers.expect(requestTo("https://api.github.com/user/emails?per_page=100"))
                .andRespond(withServerError());

        returnFrom("github", authorizeAtGitHub())
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/auth/callback?error=provider_error"));

        providers.verify();
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void aCancelledConsentSendsTheSignInBackToTheSite() throws Exception {
        final var authorization = authorize("github");

        mockMvc.perform(get(CALLBACK + "github")
                        .param("error", "access_denied")
                        .param("state", queryParameter(authorization, "state"))
                        .session((MockHttpSession) authorization.getRequest().getSession(false)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/auth/callback?error=provider_error"));

        verifyNoInteractions(tokenProvider, userRepository);
    }

    @Test
    void aGoogleSignInWithAVerifiedEmailStillSignsIn() throws Exception {
        final var member = User.builder().id(8L).email("someone@gmail.com").active(true).authProvider("google")
                .authorities(List.of(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
        when(userRepository.findByEmail("someone@gmail.com")).thenReturn(Optional.of(member));
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");
        expectGoogleUserInfo(true);
        final var authorization = authorize("google");
        assertThat(queryParameter(authorization, "scope")).isEqualTo("profile email");
        assertThat(queryParameter(authorization, "redirect_uri")).isEqualTo("https://hrva.cc/login/oauth2/code/google");

        returnFrom("google", authorization)
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/auth/callback?token=jwt-token"));

        providers.verify();
    }

    @Test
    void aGoogleAccountWithAnUnverifiedEmailIsSentBackWithTheReason() throws Exception {
        expectGoogleUserInfo(false);

        returnFrom("google", authorize("google"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/auth/callback?error=google_email_unverified"));

        providers.verify();
        verify(userRepository, never()).findByEmail(anyString());
    }

    private MvcResult authorizeAtGitHub() throws Exception {
        final var authorization = authorize("github");

        final var gitHubUrl = UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl()).build();
        assertThat(gitHubUrl.getScheme() + "://" + gitHubUrl.getHost() + gitHubUrl.getPath()).isEqualTo("https://github.com/login/oauth/authorize");
        assertThat(queryParameter(authorization, "client_id")).isEqualTo("test-github-client");
        assertThat(queryParameter(authorization, "scope")).isEqualTo("user:email");
        assertThat(queryParameter(authorization, "redirect_uri")).isEqualTo("https://hrva.cc/login/oauth2/code/github");

        return authorization;
    }

    private MvcResult authorize(final String provider) throws Exception {
        return mockMvc.perform(get("/oauth2/authorization/" + provider))
                .andExpect(status().isFound())
                .andReturn();
    }

    private ResultActions returnFrom(final String provider, final MvcResult authorization) throws Exception {
        return mockMvc.perform(get(CALLBACK + provider)
                .param("code", "github-authorization-code")
                .param("state", queryParameter(authorization, "state"))
                .session((MockHttpSession) authorization.getRequest().getSession(false)));
    }

    private void expectProfileAndEmails(final String emails) {
        providers.expect(requestTo("https://api.github.com/user"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess(PROFILE, MediaType.APPLICATION_JSON));
        providers.expect(requestTo("https://api.github.com/user/emails?per_page=100"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess(emails, MediaType.APPLICATION_JSON));
    }

    private void expectGoogleUserInfo(final boolean emailVerified) {
        providers.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess("""
                        {"sub": "1057", "name": "Someone", "email": "someone@gmail.com", "email_verified": %s}""".formatted(emailVerified),
                        MediaType.APPLICATION_JSON));
    }

    private static String queryParameter(final MvcResult authorization, final String name) {
        final var value = UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl()).build()
                .getQueryParams().getFirst(name);
        assertThat(value).isNotNull();

        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            OAuth2ClientConfiguration.class,
            GlobalExceptionHandler.class,
            JwtFilter.class,
            ClientIpResolver.class,
            OAuth2LoginSuccessHandler.class
    })
    static class TestApplication {

        @Bean
        RestTemplate providerApi() {
            return new RestTemplate();
        }

        @Bean
        MockRestServiceServer providers(final RestTemplate providerApi) {
            return MockRestServiceServer.bindTo(providerApi).build();
        }

        @Bean
        @DependsOn("providers")
        OAuth2LoginUserService oAuth2LoginUserService(final RestTemplate providerApi) {
            final var userInfo = new DefaultOAuth2UserService();
            userInfo.setRestOperations(providerApi);

            return new OAuth2LoginUserService(userInfo, RestClient.create(providerApi));
        }

        @Bean
        @Primary
        OAuth2UserService<OAuth2UserRequest, OAuth2User> anotherUserService() {
            return userRequest -> {
                throw new IllegalStateException("oauth2Login must use OAuth2LoginUserService, not whichever user service bean wins");
            };
        }

        @Bean
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenEndpoint() {
            return grantRequest -> OAuth2AccessTokenResponse.withToken(ACCESS_TOKEN)
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .scopes(Set.of("user:email"))
                    .build();
        }

    }

}
