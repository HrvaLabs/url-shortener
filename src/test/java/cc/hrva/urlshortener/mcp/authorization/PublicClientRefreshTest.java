package cc.hrva.urlshortener.mcp.authorization;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpAccessTokens;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = PublicClientRefreshTest.TestApplication.class,
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
@ExtendWith(OutputCaptureExtension.class)
class PublicClientRefreshTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String EMAIL = "user@localhost";
    private static final Duration REFRESH_TOKEN_TIME_TO_LIVE = Duration.ofDays(30);
    private static final String PUBLIC_REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none","grant_types":["authorization_code","refresh_token"]}""";
    private static final String PUBLIC_REGISTRATION_WITHOUT_GRANT_TYPES = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none"}""";
    private static final String FOREIGN_RESOURCE = "https://evil.example/mcp";
    private static final String CONFIDENTIAL_REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"client_secret_basic","grant_types":["authorization_code","refresh_token"]}""";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    @Qualifier("mcpJwtDecoder")
    private JwtDecoder mcpJwtDecoder;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @MockitoBean
    private RateLimiter rateLimiter;

    private McpAccessTokens accessTokens;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        accessTokens = new McpAccessTokens(mockMvc);
    }

    @Test
    void publicClientCodeExchangeReturnsRefreshToken() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);

        final var tokens = exchangeCode(clientId);

        assertThat(tokens.get("refresh_token")).isInstanceOf(String.class).asString().isNotBlank();
        assertRefreshTokenLivesThirtyDays((String) tokens.get("refresh_token"));
    }

    @Test
    void refreshRotatesAndOldRefreshFails(final CapturedOutput output) throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var issued = exchangeCode(clientId);
        final var oldAccessToken = (String) issued.get("access_token");
        final var oldRefreshToken = (String) issued.get("refresh_token");

        final var refreshed = refresh(clientId, oldRefreshToken, RESOURCE);
        final var newAccessToken = (String) refreshed.get("access_token");
        final var newRefreshToken = (String) refreshed.get("refresh_token");

        assertThat(newAccessToken).isNotBlank().isNotEqualTo(oldAccessToken);
        assertThat(newRefreshToken).isNotBlank().isNotEqualTo(oldRefreshToken);
        assertThat(mcpJwtDecoder.decode(newAccessToken).getAudience()).containsExactly(RESOURCE);
        assertRefreshTokenLivesThirtyDays(newRefreshToken);
        mockMvc.perform(mcpRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccessToken))
                .andExpect(status().isOk());
        mockMvc.perform(mcpRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(refreshRequest(clientId, oldRefreshToken, RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist());

        assertThat(refresh(clientId, newRefreshToken, RESOURCE).get("refresh_token")).isNotEqualTo(newRefreshToken);
        assertThat(output.toString())
                .doesNotContain(oldRefreshToken)
                .doesNotContain(newRefreshToken)
                .doesNotContain(oldAccessToken)
                .doesNotContain(newAccessToken);
    }

    @Test
    void refreshWithoutResourceStillYieldsMcpAudience() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var issued = exchangeCode(clientId);

        final var firstRefresh = refresh(clientId, (String) issued.get("refresh_token"), null);
        final var secondRefresh = refresh(clientId, (String) firstRefresh.get("refresh_token"), null);

        assertThat(mcpJwtDecoder.decode((String) firstRefresh.get("access_token")).getAudience()).containsExactly(RESOURCE);
        assertThat(mcpJwtDecoder.decode((String) secondRefresh.get("access_token")).getAudience()).containsExactly(RESOURCE);
        mockMvc.perform(mcpRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + secondRefresh.get("access_token")))
                .andExpect(status().isOk());
    }

    @Test
    void codeExchangeWithoutResourceStillYieldsMcpAudience() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var code = accessTokens.authorize(clientId, EMAIL, null);
        final var body = mockMvc.perform(codeExchangeRequestWithoutResource(clientId, code))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final String accessToken = JsonPath.read(body, "$.access_token");
        final String refreshToken = JsonPath.read(body, "$.refresh_token");

        assertThat(mcpJwtDecoder.decode(accessToken).getAudience()).containsExactly(RESOURCE);
        mockMvc.perform(mcpRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());

        final var refreshed = refresh(clientId, refreshToken, null);

        assertThat(mcpJwtDecoder.decode((String) refreshed.get("access_token")).getAudience()).containsExactly(RESOURCE);
        mockMvc.perform(mcpRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed.get("access_token")))
                .andExpect(status().isOk());
    }

    @Test
    void refreshForRemovedAuthorizationIsInvalidGrant() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");
        final var authorization = authorizationService.findByToken(refreshToken, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(authorization).isNotNull();

        authorizationService.remove(authorization);

        mockMvc.perform(refreshRequest(clientId, refreshToken, RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void refreshTokenOfAnotherClientIsInvalidGrant() throws Exception {
        final var victimClientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var victimRefreshToken = (String) exchangeCode(victimClientId).get("refresh_token");
        final var otherClientId = accessTokens.register(PUBLIC_REGISTRATION);

        mockMvc.perform(refreshRequest(otherClientId, victimRefreshToken, RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void refreshWithUnknownClientIdIsInvalidClient() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(refreshRequest("unknown-client", refreshToken, RESOURCE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void publicClientRegisteredWithoutGrantTypesStillGetsRefreshToken() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION_WITHOUT_GRANT_TYPES);

        final var tokens = exchangeCode(clientId);

        assertThat(tokens).containsKeys("access_token", "refresh_token");
        assertThat(refresh(clientId, (String) tokens.get("refresh_token"), RESOURCE)).containsKeys("access_token", "refresh_token");
    }

    @Test
    void introspectionDoesNotAcceptPublicClientRefreshAuthentication() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("client_id", clientId)
                        .param("grant_type", "refresh_token")
                        .param("token", refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.active").doesNotExist());
    }

    @Test
    void revocationDoesNotAcceptPublicClientRefreshAuthentication() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(post("/oauth2/revoke")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("client_id", clientId)
                        .param("grant_type", "refresh_token")
                        .param("token", refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        assertThat(authorizationService.findByToken(refreshToken, OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
    }

    @Test
    void credentialLessRefreshWithoutClientIdIsInvalidClient() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(post("/oauth2/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("resource", RESOURCE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void refreshWithEmptyClientSecretIsTreatedAsPublicClient() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(refreshRequest(clientId, refreshToken, RESOURCE).param("client_secret", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty());
    }

    @Test
    void refreshWithScopeBeyondOriginalGrantIsInvalidScope() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(refreshRequest(clientId, refreshToken, RESOURCE).param("scope", "claudeai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void codeExchangeWithForeignResourceIsInvalidTarget() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var code = accessTokens.authorize(clientId, EMAIL, RESOURCE);

        mockMvc.perform(codeExchangeRequestWithoutResource(clientId, code).param("resource", FOREIGN_RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void codeExchangeWithDuplicateResourceIsInvalidTarget() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var code = accessTokens.authorize(clientId, EMAIL, RESOURCE);

        mockMvc.perform(codeExchangeRequestWithoutResource(clientId, code).param("resource", RESOURCE, RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void refreshWithForeignResourceIsInvalidTarget() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(refreshRequest(clientId, refreshToken, FOREIGN_RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void refreshWithDuplicateResourceIsInvalidTarget() throws Exception {
        final var clientId = accessTokens.register(PUBLIC_REGISTRATION);
        final var refreshToken = (String) exchangeCode(clientId).get("refresh_token");

        mockMvc.perform(refreshRequest(clientId, refreshToken, RESOURCE).param("resource", FOREIGN_RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void confidentialClientCannotRefreshWithClientIdAlone() throws Exception {
        final var body = mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content(CONFIDENTIAL_REGISTRATION))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final String clientId = JsonPath.read(body, "$.client_id");
        final String clientSecret = JsonPath.read(body, "$.client_secret");
        final var basic = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        final var code = accessTokens.authorize(clientId, EMAIL, RESOURCE);
        final var tokenBody = mockMvc.perform(codeExchangeRequest(clientId, code).header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final String refreshToken = JsonPath.read(tokenBody, "$.refresh_token");

        mockMvc.perform(refreshRequest(clientId, refreshToken, RESOURCE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.access_token").doesNotExist());

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basic)
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty());
    }

    private Map<String, Object> exchangeCode(final String clientId) throws Exception {
        final var code = accessTokens.authorize(clientId, EMAIL, RESOURCE);
        final var body = mockMvc.perform(codeExchangeRequest(clientId, code))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$");
    }

    private Map<String, Object> refresh(final String clientId, final String refreshToken, final String resource) throws Exception {
        final var body = mockMvc.perform(refreshRequest(clientId, refreshToken, resource))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$");
    }

    private void assertRefreshTokenLivesThirtyDays(final String refreshToken) {
        final var authorization = authorizationService.findByToken(refreshToken, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(authorization).isNotNull();
        assertThat(authorization.getRefreshToken()).isNotNull();
        assertThat(authorization.getRefreshToken().getToken().getExpiresAt())
                .isCloseTo(Instant.now().plus(REFRESH_TOKEN_TIME_TO_LIVE), within(Duration.ofMinutes(1)));
    }

    private static MockHttpServletRequestBuilder codeExchangeRequest(final String clientId, final String code) {
        return codeExchangeRequestWithoutResource(clientId, code).param("resource", RESOURCE);
    }

    private static MockHttpServletRequestBuilder codeExchangeRequestWithoutResource(final String clientId, final String code) {
        return post("/oauth2/token")
                .contentType(APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", McpAccessTokens.REDIRECT_URI)
                .param("code_verifier", McpAccessTokens.CODE_VERIFIER);
    }

    private static MockHttpServletRequestBuilder refreshRequest(final String clientId, final String refreshToken, final String resource) {
        final var request = post("/oauth2/token")
                .contentType(APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken);

        return resource == null ? request : request.param("resource", resource);
    }

    private static MockHttpServletRequestBuilder mcpRequest() {
        return post("/mcp")
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .contentType(APPLICATION_JSON)
                .content(TOOLS_LIST);
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
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class
    })
    static class TestApplication {
    }

}
