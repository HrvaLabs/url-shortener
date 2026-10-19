package cc.hrva.urlshortener.mcp.authorization;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.RequestDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AuthorizationServerConfigurationTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class AuthorizationServerConfigurationTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String REGISTERED_REDIRECT_URI = "http://localhost:53682/callback";
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CODE_CHALLENGE = s256(CODE_VERIFIER);
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final String REGISTRATION_WITH_SCOPE = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none","scope":"%s"}""";
    private static final String SCOPED_REGISTRATION = REGISTRATION_WITH_SCOPE.formatted("claudeai");
    private static final String REGISTRATION_WITH_METADATA = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none",%s}""";
    private static final String EMPTY_SCOPE_REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none","scope":""}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    @Qualifier("mcpJwtDecoder")
    private JwtDecoder mcpJwtDecoder;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @Test
    void metadataReportsFixedIssuerDespiteForwardedHeaders() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/oauth2/jwks"))
                .andExpect(jsonPath("$.registration_endpoint").value(ISSUER + "/oauth2/register"))
                .andExpect(jsonPath("$.code_challenge_methods_supported", hasItem("S256")))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("none")))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void dcrAcceptsEmptyScopePublicClient() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content(EMPTY_SCOPE_REGISTRATION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").isNotEmpty())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"));
    }

    @Test
    void dcrClientIsStoredWithOneHourAccessTokensPkceAndNoConsentScreen() throws Exception {
        final var clientId = registerClient();

        final var registeredClient = registeredClientRepository.findByClientId(clientId);

        assertThat(registeredClient).isNotNull();
        assertThat(registeredClient.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofHours(1));
        assertThat(registeredClient.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(registeredClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(registeredClient.getRedirectUris()).containsExactly(REGISTERED_REDIRECT_URI);
    }

    @Test
    void authorizeAcceptsLocalhostWithDifferentPort() throws Exception {
        final var clientId = registerClient();

        final var response = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", "http://localhost:61111/callback")
                        .queryParam("code_challenge", CODE_CHALLENGE)
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", "state-1")
                        .session(signedInSession()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse();

        final var location = response.getHeader(HttpHeaders.LOCATION);
        assertThat(location)
                .startsWith("http://localhost:61111/callback?code=")
                .contains("state=state-1")
                .doesNotContain("error");

        final var code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        final var authorization = authorizationService.findByToken(code, new OAuth2TokenType(OAuth2ParameterNames.CODE));
        assertThat(authorization).isNotNull();
        assertThat(authorization.getPrincipalName()).isEqualTo("user@localhost");
        assertThat(authorization.<Authentication>getAttribute(Principal.class.getName()))
                .extracting(Authentication::getName)
                .isEqualTo("user@localhost");
    }

    @Test
    void dcrAcceptsAndStoresNonEmptyScope() throws Exception {
        final var body = mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content(SCOPED_REGISTRATION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("claudeai"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        final var registeredClient = registeredClientRepository.findByClientId(JsonPath.read(body, "$.client_id"));

        assertThat(registeredClient).isNotNull();
        assertThat(registeredClient.getScopes()).containsExactly("claudeai");
    }

    @Test
    void authorizeAcceptsScopeTheClientRegistered() throws Exception {
        final var clientId = registerClient(SCOPED_REGISTRATION);

        final var location = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", "http://localhost:61112/callback")
                        .queryParam("scope", "claudeai")
                        .queryParam("code_challenge", CODE_CHALLENGE)
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", "state-2")
                        .session(signedInSession()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        assertThat(location)
                .startsWith("http://localhost:61112/callback?code=")
                .contains("state=state-2")
                .doesNotContain("error");

        final var code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        final var authorization = authorizationService.findByToken(code, new OAuth2TokenType(OAuth2ParameterNames.CODE));
        assertThat(authorization).isNotNull();
        assertThat(authorization.getAuthorizedScopes()).containsExactly("claudeai");
    }

    @Test
    void dcrRejectsOpenidScope() throws Exception {
        for (final var scope : List.of("openid", "claudeai openid")) {
            mockMvc.perform(post("/oauth2/register")
                            .contentType(APPLICATION_JSON)
                            .content(REGISTRATION_WITH_SCOPE.formatted(scope)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_scope"));
        }
    }

    @Test
    void dcrRejectsMalformedScopeTokens() throws Exception {
        for (final var scope : List.of("claudeai,openid", ",openid,", "claudeai  x", " claudeai", "clau\\\"deai", "clau\\\\deai")) {
            mockMvc.perform(post("/oauth2/register")
                            .contentType(APPLICATION_JSON)
                            .content(REGISTRATION_WITH_SCOPE.formatted(scope)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_scope"))
                    .andExpect(jsonPath("$.error_description").value("Invalid Client Registration: scope"));
        }
    }

    @Test
    void dcrRejectsScopeSentAsJsonArray() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none","scope":["claudeai","openid"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void registeredScopesReloadExactlyAsRegistered() throws Exception {
        final var clientId = registerClient(REGISTRATION_WITH_SCOPE.formatted("claudeai mcp:tools"));

        final var registeredClient = registeredClientRepository.findByClientId(clientId);

        assertThat(registeredClient).isNotNull();
        assertThat(registeredClient.getScopes())
                .containsExactlyInAnyOrder("claudeai", "mcp:tools")
                .doesNotContain("openid");
    }

    @Test
    void dcrRejectsRedirectUriContainingComma() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["http://localhost:1/cb,https://evil.example/cb"],"token_endpoint_auth_method":"none"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void dcrRejectsGrantTypesOtherThanAuthorizationCodeAndRefreshToken() throws Exception {
        for (final var grantTypes : List.of(
                "[\"client_credentials\"]",
                "[\"authorization_code\",\"client_credentials\"]",
                "[\"authorization_code,client_credentials\"]",
                "[\"password\"]",
                "[\"urn:ietf:params:oauth:grant-type:jwt-bearer\"]",
                "[\"urn:ietf:params:oauth:grant-type:device_code\"]")) {
            mockMvc.perform(post("/oauth2/register")
                            .contentType(APPLICATION_JSON)
                            .content(REGISTRATION_WITH_METADATA.formatted("\"grant_types\":" + grantTypes)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
                    .andExpect(jsonPath("$.error_description").value("Invalid Client Registration: grant_types"));
        }
    }

    @Test
    void dcrAcceptsAuthorizationCodeWithRefreshToken() throws Exception {
        final var clientId = registerClient(REGISTRATION_WITH_METADATA.formatted("\"grant_types\":[\"authorization_code\",\"refresh_token\"]"));

        assertThat(registeredClientRepository.findByClientId(clientId).getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
    }

    @Test
    void dcrDefaultsToAuthorizationCodeWhenGrantTypesAreAbsent() throws Exception {
        final var clientId = registerClient();

        assertThat(registeredClientRepository.findByClientId(clientId).getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
    }

    @Test
    void dcrRejectsResponseTypesOtherThanCode() throws Exception {
        for (final var responseTypes : List.of("[\"token\"]", "[\"code\",\"token\"]", "[\"code id_token\"]")) {
            mockMvc.perform(post("/oauth2/register")
                            .contentType(APPLICATION_JSON)
                            .content(REGISTRATION_WITH_METADATA.formatted("\"response_types\":" + responseTypes)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
                    .andExpect(jsonPath("$.error_description").value("Invalid Client Registration: response_types"));
        }

        registerClient(REGISTRATION_WITH_METADATA.formatted("\"response_types\":[\"code\"]"));
    }

    @Test
    void clientCredentialsTokenRequestFailsForDcrClient() throws Exception {
        final var body = mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"client_secret_basic"}"""))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final String clientId = JsonPath.read(body, "$.client_id");
        final String clientSecret = JsonPath.read(body, "$.client_secret");
        final var basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("resource", ISSUER + "/mcp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void dcrStillValidatesRedirectUris() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["javascript:alert(1)"],"token_endpoint_auth_method":"none"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    }

    @Test
    void authorizeWithoutSignInAnswers401() throws Exception {
        final var clientId = registerClient();

        mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", REGISTERED_REDIRECT_URI)
                        .queryParam("code_challenge", CODE_CHALLENGE)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void openidConfigurationIs404() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathSuffixedAuthorizationServerMetadataIs404() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server/mcp"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownClientIdAnswers400NotLoginRedirect() throws Exception {
        mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "unknown-client")
                        .queryParam("redirect_uri", REGISTERED_REDIRECT_URI)
                        .queryParam("code_challenge", CODE_CHALLENGE)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void jwksPublishesOnlyTheConfiguredPublicKey() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kid").value(SIGNING_KEY.getKeyID()))
                .andExpect(jsonPath("$.keys[0].n").value(SIGNING_KEY.getModulus().toString()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    @Test
    void mcpJwtDecoderAcceptsOwnTokensAndRejectsForeignIssuer() {
        final var ownToken = encode(ISSUER);
        final var foreignToken = encode("https://evil.example");

        assertThat(mcpJwtDecoder.decode(ownToken).getSubject()).isEqualTo("user@localhost");
        assertThatThrownBy(() -> mcpJwtDecoder.decode(foreignToken))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void signingKeyWithoutPrivatePartIsRejected() {
        final var publicOnly = SIGNING_KEY.toPublicJWK().toJSONString();

        assertThatThrownBy(() -> AuthorizationServerConfiguration.parseSigningKey(publicOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.signing-key");
        assertThatThrownBy(() -> AuthorizationServerConfiguration.parseSigningKey("not a jwk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.signing-key");
    }

    @Test
    void googleOauthLoginStillRedirects() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("https://accounts.google.com/")));
    }

    @Test
    void existingApiStillRequiresAppJwt() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appJwtFilterRunsOnlyInsideItsSecurityChain() throws Exception {
        mockMvc.perform(get("/api/changelog").header(HttpHeaders.AUTHORIZATION, "Bearer app-jwt"));

        verify(tokenProvider, times(1)).validateToken("app-jwt");
    }

    @Test
    void appJwtIsNotConsultedOnAuthorizationServerPaths() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server").header(HttpHeaders.AUTHORIZATION, "Bearer app-jwt"))
                .andExpect(status().isOk());

        verifyNoInteractions(tokenProvider);
    }

    private String registerClient() throws Exception {
        return registerClient(EMPTY_SCOPE_REGISTRATION);
    }

    private String registerClient(final String registration) throws Exception {
        final var body = mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.client_id");
    }

    private static MockHttpSession signedInSession() {
        final var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        final var principal = new User("user@localhost", "", authorities);
        final var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        final var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

        return session;
    }

    private static String encode(final String issuer) {
        final var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(SIGNING_KEY)));
        final var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("user@localhost")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private static String s256(final String verifier) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            SecurityConfiguration.class,
            AuthorizationServerConfiguration.class,
            JwtFilter.class
    })
    static class TestApplication {
    }

}
