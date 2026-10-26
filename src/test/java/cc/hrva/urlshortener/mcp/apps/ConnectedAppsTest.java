package cc.hrva.urlshortener.mcp.apps;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpAccessTokens;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ConnectedAppsTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/mcp-connector-test-changelog.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=apGSsEZu0Olb7Yg+5fX8BSmN8Fk/UTXzdN0wnWq+EHLW2fT4ss1KuzwV2xo3V2uC/3ZUEphbQUbSl0VBs3bX4g==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ConnectedAppsTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String CONNECTED_APPS = "/api/v1/users/me/connected-apps";
    private static final String CONNECTED_APP = CONNECTED_APPS + "/{clientId}";
    private static final String EMAIL = "user@localhost";
    private static final String OTHER_EMAIL = "other@localhost";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String OTHER_CLIENT_IP = "198.51.100.23";
    private static final String PROXY_IP = "10.0.0.1";
    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String NAMED_REGISTRATION = """
            {"client_name":"%s","redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none"}""";
    private static final String INVALID_REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none","grant_types":["client_credentials"]}""";
    private static final String RATE_LIMITED = """
            {"error":"rate_limited","error_description":"Too many app registrations from this network. Try again later."}""";
    private static final String TEMPORARILY_UNAVAILABLE = """
            {"error":"temporarily_unavailable"}""";
    private static final String DCR_RATE_LIMIT_KEY_PATTERN = "rate-limit:dcr:[0-9a-f]{64}";
    private static final String ISO_INSTANT_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectedAppsService connectedAppsService;

    @Autowired
    private DcrClientCleanupJob dcrClientCleanupJob;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private TokenIssueHook tokenIssueHook;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private final InMemoryRedis redis = new InMemoryRedis();
    private McpAccessTokens accessTokens;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        tokenIssueHook.disarm();
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent");
        jdbcTemplate.update("DELETE FROM oauth2_authorization");
        jdbcTemplate.update("DELETE FROM oauth2_registered_client");
        redis.backing(redisTemplate);
        accessTokens = new McpAccessTokens(mockMvc);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(User.builder().id(7L).email(EMAIL).active(true).build()));
        when(userRepository.findByEmail(OTHER_EMAIL)).thenReturn(Optional.of(User.builder().id(8L).email(OTHER_EMAIL).active(true).build()));
    }

    @Test
    void listsOnlyCurrentUsersApps() throws Exception {
        final var claude = connect("Claude", EMAIL);
        final var cursor = connect("Cursor", EMAIL);
        final var othersApp = connect("Other app", OTHER_EMAIL);
        final var abandonedClientId = accessTokens.register(NAMED_REGISTRATION.formatted("Abandoned"));
        accessTokens.authorize(abandonedClientId, EMAIL, RESOURCE);
        final var twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        final var oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        jdbcTemplate.update("UPDATE oauth2_authorization SET authorization_code_issued_at = ?, access_token_issued_at = ? WHERE registered_client_id = ?",
                Timestamp.from(twoDaysAgo), Timestamp.from(oneDayAgo), registeredClientId(claude.clientId()));

        final var body = mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].clientId").value(cursor.clientId()))
                .andExpect(jsonPath("$[0].clientName").value("Cursor"))
                .andExpect(jsonPath("$[1].clientId").value(claude.clientId()))
                .andExpect(jsonPath("$[1].clientName").value("Claude"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        final List<Map<String, Object>> apps = JsonPath.read(body, "$");
        assertThat(apps).allSatisfy(app -> {
            assertThat(app).containsOnlyKeys("clientId", "clientName", "firstConnected", "lastUsed");
            assertThat((String) app.get("firstConnected")).matches(ISO_INSTANT_PATTERN);
            assertThat((String) app.get("lastUsed")).matches(ISO_INSTANT_PATTERN);
        });
        assertThat(Instant.parse((String) apps.get(1).get("firstConnected"))).isEqualTo(twoDaysAgo);
        assertThat(Instant.parse((String) apps.get(1).get("lastUsed"))).isEqualTo(oneDayAgo);
        assertThat(Instant.parse((String) apps.get(0).get("lastUsed"))).isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
        assertThat(body)
                .doesNotContain(othersApp.clientId())
                .doesNotContain("Other app")
                .doesNotContain(abandonedClientId)
                .doesNotContain("Abandoned");

        mockMvc.perform(refreshRequest(claude))
                .andExpect(status().isOk());
        accessTokens.issueTokens(claude.clientId(), EMAIL, RESOURCE);

        final var afterRefresh = mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].clientId").value(claude.clientId()))
                .andExpect(jsonPath("$[1].clientId").value(cursor.clientId()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(Instant.parse(JsonPath.read(afterRefresh, "$[0].firstConnected"))).isEqualTo(twoDaysAgo);

        mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(OTHER_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].clientId").value(othersApp.clientId()))
                .andExpect(jsonPath("$[0].clientName").value("Other app"));
        mockMvc.perform(get(CONNECTED_APPS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disconnectRevokesAccessAndRefreshImmediately(final CapturedOutput output) throws Exception {
        final var app = connect("Claude", EMAIL);
        final var sameAppOfOtherUser = connectExisting(app.clientId(), OTHER_EMAIL);
        jdbcTemplate.update("INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) VALUES (?, ?, ?)",
                registeredClientId(app.clientId()), EMAIL, "SCOPE_claudeai");
        mockMvc.perform(mcpRequest(app.accessToken()))
                .andExpect(status().isOk());
        final var outputBeforeDisconnect = output.length();

        mockMvc.perform(delete(CONNECTED_APP, app.clientId()).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isNoContent());

        mockMvc.perform(mcpRequest(app.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(refreshRequest(app))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(rowsOf("oauth2_authorization", EMAIL)).isZero();
        assertThat(rowsOf("oauth2_authorization_consent", EMAIL)).isZero();

        mockMvc.perform(mcpRequest(sameAppOfOtherUser.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(OTHER_EMAIL))))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete(CONNECTED_APP, app.clientId()).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isNotFound());
        assertThat(output.toString().substring(outputBeforeDisconnect))
                .contains("Disconnected an MCP client")
                .doesNotContain(EMAIL)
                .doesNotContain("Claude")
                .doesNotContain(app.accessToken())
                .doesNotContain(app.refreshToken());
    }

    @Test
    void refreshRacingADisconnectCannotRestoreTheApp(final CapturedOutput output) throws Exception {
        final var app = connect("Claude", EMAIL);
        tokenIssueHook.armOnce(() -> connectedAppsService.disconnect(EMAIL, app.clientId()));
        final var outputBeforeRefresh = output.length();

        mockMvc.perform(refreshRequest(app))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist());

        assertThat(tokenIssueHook.fired()).isTrue();
        assertThat(rowsOf("oauth2_authorization", EMAIL)).isZero();
        mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(mcpRequest(app.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(refreshRequest(app))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(rowsOf("oauth2_authorization", EMAIL)).isZero();
        assertThat(output.toString().substring(outputBeforeRefresh))
                .doesNotContain(EMAIL)
                .doesNotContain(app.accessToken())
                .doesNotContain(app.refreshToken());
    }

    @Test
    void savingAnAuthorizationWithTokensNeverReinsertsADeletedRow() throws Exception {
        final var app = connect("Claude", EMAIL);
        final var authorization = authorizationService.findByToken(app.refreshToken(), OAuth2TokenType.REFRESH_TOKEN);
        assertThat(authorization).isNotNull();
        final var refreshed = OAuth2Authorization.from(authorization)
                .invalidate(authorization.getRefreshToken().getToken())
                .build();
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE id = ?", authorization.getId());

        assertThatThrownBy(() -> authorizationService.save(refreshed))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));

        assertThat(authorizationService.findById(authorization.getId())).isNull();
        assertThat(rowsOf("oauth2_authorization", EMAIL)).isZero();
    }

    @Test
    void savingAnExistingAuthorizationWithTokensStillUpdatesIt() throws Exception {
        final var app = connect("Claude", EMAIL);
        final var authorization = authorizationService.findByToken(app.refreshToken(), OAuth2TokenType.REFRESH_TOKEN);
        assertThat(authorization).isNotNull();

        authorizationService.save(OAuth2Authorization.from(authorization).invalidate(authorization.getRefreshToken().getToken()).build());

        final var saved = authorizationService.findById(authorization.getId());
        assertThat(saved).isNotNull();
        assertThat(saved.getRefreshToken()).isNotNull();
        assertThat(saved.getRefreshToken().isInvalidated()).isTrue();
        assertThat(rowsOf("oauth2_authorization", EMAIL)).isEqualTo(1);
    }

    @Test
    void disconnectOtherUsersAppIs404() throws Exception {
        final var othersApp = connect("Other app", OTHER_EMAIL);
        final var registeredButUnused = accessTokens.register(NAMED_REGISTRATION.formatted("Unused"));

        final var foreign = mockMvc.perform(delete(CONNECTED_APP, othersApp.clientId()).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final var unknown = mockMvc.perform(delete(CONNECTED_APP, "no-such-client").header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final var unused = mockMvc.perform(delete(CONNECTED_APP, registeredButUnused).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(EMAIL))))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreign).isEqualTo(unknown).isEqualTo(unused).doesNotContain(othersApp.clientId());
        mockMvc.perform(delete(CONNECTED_APP, othersApp.clientId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpRequest(othersApp.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONNECTED_APPS).header(HttpHeaders.AUTHORIZATION, bearer(appJwt(OTHER_EMAIL))))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].clientId").value(othersApp.clientId()));
    }

    @Test
    void twentyFirstRegistrationIs429(final CapturedOutput output) throws Exception {
        for (int attempt = 1; attempt <= 20; attempt++) {
            if (attempt % 5 == 0) {
                mockMvc.perform(registration(INVALID_REGISTRATION).with(from(CLIENT_IP)))
                        .andExpect(status().isBadRequest());
            } else {
                mockMvc.perform(registration(NAMED_REGISTRATION.formatted("App " + attempt)).with(from(CLIENT_IP)))
                        .andExpect(status().isCreated());
            }
        }

        mockMvc.perform(registration(NAMED_REGISTRATION.formatted("App 21")).with(from(CLIENT_IP)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"))
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json(RATE_LIMITED, true));
        mockMvc.perform(registration(INVALID_REGISTRATION).with(from(CLIENT_IP)))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(registration(NAMED_REGISTRATION.formatted("Other network")).with(from(OTHER_CLIENT_IP)))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth2_registered_client", Integer.class)).isEqualTo(17);
        assertThat(redis.keys()).hasSize(2).allSatisfy(key -> {
            assertThat(key).matches(DCR_RATE_LIMIT_KEY_PATTERN);
            assertThat(key).doesNotContain(CLIENT_IP).doesNotContain(OTHER_CLIENT_IP);
        });
        assertThat(output.toString())
                .contains("Client registration refused by the rate limit")
                .doesNotContain(CLIENT_IP)
                .doesNotContain(OTHER_CLIENT_IP);
    }

    @Test
    void registrationRateLimitCountsTheCloudflareClientNotTheProxy() throws Exception {
        for (int attempt = 1; attempt <= 20; attempt++) {
            mockMvc.perform(registration(NAMED_REGISTRATION.formatted("App " + attempt)).with(from(PROXY_IP)).header(CF_CONNECTING_IP, CLIENT_IP))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(registration(NAMED_REGISTRATION.formatted("App 21")).with(from(PROXY_IP)).header(CF_CONNECTING_IP, CLIENT_IP))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(registration(NAMED_REGISTRATION.formatted("Other network")).with(from(PROXY_IP)).header(CF_CONNECTING_IP, OTHER_CLIENT_IP))
                .andExpect(status().isCreated());
    }

    @Test
    void registrationFailsClosedWhenRateLimiterIsUnavailable(final CapturedOutput output) throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal/10.0.0.5:6379"));
        final var outputBeforeRegistration = output.length();

        mockMvc.perform(registration(NAMED_REGISTRATION.formatted("Claude")).with(from(CLIENT_IP)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json(TEMPORARILY_UNAVAILABLE, true))
                .andExpect(content().string(not(containsString("redis.internal"))));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth2_registered_client", Integer.class)).isZero();
        final var logged = output.toString().substring(outputBeforeRegistration);
        assertThat(logged.lines())
                .anySatisfy(line -> assertThat(line).contains("WARN").contains("Client registration refused because the rate limiter is unavailable"));
        assertThat(logged).doesNotContain(CLIENT_IP);
    }

    @Test
    void cleanupDeletesOnlyStaleClientsWithoutAuthorizations(final CapturedOutput output) throws Exception {
        final var staleUnused = accessTokens.register(NAMED_REGISTRATION.formatted("Stale unused"));
        final var staleConnected = connect("Stale connected", EMAIL);
        final var freshUnused = accessTokens.register(NAMED_REGISTRATION.formatted("Fresh unused"));
        final var allExpired = connect("All expired", EMAIL);
        final var staleAllExpired = connect("Stale all expired", OTHER_EMAIL);
        final var refreshable = connect("Refreshable", EMAIL);
        final var pendingCode = accessTokens.register(NAMED_REGISTRATION.formatted("Pending code"));
        accessTokens.authorize(pendingCode, EMAIL, RESOURCE);
        final var expiredCode = accessTokens.register(NAMED_REGISTRATION.formatted("Expired code"));
        accessTokens.authorize(expiredCode, EMAIL, RESOURCE);
        final var thirtyOneDaysAgo = Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS));
        for (final var staleClientId : List.of(staleUnused, staleConnected.clientId(), staleAllExpired.clientId())) {
            jdbcTemplate.update("UPDATE oauth2_registered_client SET client_id_issued_at = ? WHERE client_id = ?", thirtyOneDaysAgo, staleClientId);
        }
        for (final var expiredClientId : List.of(allExpired.clientId(), staleAllExpired.clientId())) {
            expire(expiredClientId, "authorization_code", "access_token", "refresh_token");
        }
        expire(refreshable.clientId(), "authorization_code", "access_token");
        expire(expiredCode, "authorization_code");
        final var outputBeforeCleanup = output.length();

        dcrClientCleanupJob.deleteStaleAuthorizationsAndClients();

        assertThat(jdbcTemplate.queryForList("SELECT client_id FROM oauth2_registered_client", String.class))
                .containsExactlyInAnyOrder(staleConnected.clientId(), freshUnused, allExpired.clientId(), refreshable.clientId(), pendingCode, expiredCode);
        assertThat(authorizationsOf(staleConnected.clientId())).isEqualTo(1);
        assertThat(authorizationsOf(refreshable.clientId())).isEqualTo(1);
        assertThat(authorizationsOf(pendingCode)).isEqualTo(1);
        assertThat(authorizationsOf(allExpired.clientId())).isZero();
        assertThat(authorizationsOf(expiredCode)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth2_authorization", Integer.class)).isEqualTo(3);
        mockMvc.perform(mcpRequest(staleConnected.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(refreshRequest(refreshable))
                .andExpect(status().isOk());
        assertThat(output.toString().substring(outputBeforeCleanup))
                .contains("expiredAuthorizations=3")
                .contains("unusedClients=2")
                .doesNotContain(EMAIL)
                .doesNotContain(OTHER_EMAIL)
                .doesNotContain("Stale")
                .doesNotContain(staleUnused);
    }

    @Test
    void disconnectAllRemovesEveryAuthorizationAndConsentOfTheUser() throws Exception {
        final var claude = connect("Claude", EMAIL);
        final var cursor = connect("Cursor", EMAIL);
        final var othersApp = connectExisting(claude.clientId(), OTHER_EMAIL);
        jdbcTemplate.update("INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) VALUES (?, ?, ?)",
                registeredClientId(cursor.clientId()), EMAIL, "SCOPE_claudeai");

        assertThat(connectedAppsService.disconnectAll(EMAIL)).isEqualTo(2);

        assertThat(rowsOf("oauth2_authorization", EMAIL)).isZero();
        assertThat(rowsOf("oauth2_authorization_consent", EMAIL)).isZero();
        mockMvc.perform(mcpRequest(claude.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpRequest(cursor.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpRequest(othersApp.accessToken()))
                .andExpect(status().isOk());
        assertThat(connectedAppsService.disconnectAll(EMAIL)).isZero();
        assertThat(connectedAppsService.disconnectAll(null)).isZero();
    }

    @Test
    void registeredClientIdIsUnique() throws Exception {
        final var clientId = accessTokens.register(NAMED_REGISTRATION.formatted("Claude"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO oauth2_registered_client (id, client_id, client_name, client_authentication_methods, authorization_grant_types, scopes, client_settings, token_settings)
                        SELECT 'duplicate', client_id, client_name, client_authentication_methods, authorization_grant_types, scopes, client_settings, token_settings
                        FROM oauth2_registered_client WHERE client_id = ?""", clientId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private Connection connect(final String clientName, final String email) throws Exception {
        return connectExisting(accessTokens.register(NAMED_REGISTRATION.formatted(clientName)), email);
    }

    private Connection connectExisting(final String clientId, final String email) throws Exception {
        final var tokens = accessTokens.issueTokens(clientId, email, RESOURCE);

        return new Connection(clientId, (String) tokens.get("access_token"), (String) tokens.get("refresh_token"));
    }

    private String registeredClientId(final String clientId) {
        return jdbcTemplate.queryForObject("SELECT id FROM oauth2_registered_client WHERE client_id = ?", String.class, clientId);
    }

    private void expire(final String clientId, final String... tokens) {
        final var issuedAt = Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS));
        final var expiredAt = Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES));
        for (final var token : tokens) {
            jdbcTemplate.update("UPDATE oauth2_authorization SET " + token + "_issued_at = ?, " + token + "_expires_at = ? WHERE registered_client_id = ?",
                    issuedAt, expiredAt, registeredClientId(clientId));
        }
    }

    private int authorizationsOf(final String clientId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_authorization a
                JOIN oauth2_registered_client c ON c.id = a.registered_client_id
                WHERE c.client_id = ?""", Integer.class, clientId);
    }

    private int rowsOf(final String table, final String principalName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE principal_name = ?", Integer.class, principalName);
    }

    private String appJwt(final String email) {
        return tokenProvider.createToken(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static String bearer(final String token) {
        return "Bearer " + token;
    }

    private static MockHttpServletRequestBuilder registration(final String body) {
        return post("/oauth2/register")
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder mcpRequest(final String accessToken) {
        return post("/mcp")
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(APPLICATION_JSON)
                .content(TOOLS_LIST);
    }

    private static MockHttpServletRequestBuilder refreshRequest(final Connection connection) {
        return post("/oauth2/token")
                .contentType(APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", connection.clientId())
                .param("refresh_token", connection.refreshToken())
                .param("resource", RESOURCE);
    }

    private static RequestPostProcessor from(final String clientIp) {
        return request -> {
            request.setRemoteAddr(clientIp);
            return request;
        };
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Connection(String clientId, String accessToken, String refreshToken) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            TokenProvider.class,
            ClientIpResolver.class,
            RedisFixedWindowRateLimiter.class,
            ConnectedAppsController.class,
            DefaultConnectedAppsService.class,
            DcrClientCleanupJob.class
    })
    static class TestApplication {

        @Bean
        TokenIssueHook tokenIssueHook() {
            return new TokenIssueHook();
        }

    }

    static final class TokenIssueHook implements OAuth2TokenCustomizer<JwtEncodingContext> {

        private final AtomicReference<Runnable> pending = new AtomicReference<>();
        private final AtomicBoolean fired = new AtomicBoolean();

        @Override
        public void customize(final JwtEncodingContext context) {
            final var action = pending.getAndSet(null);
            if (action != null) {
                fired.set(true);
                action.run();
            }
        }

        void armOnce(final Runnable action) {
            fired.set(false);
            pending.set(action);
        }

        void disarm() {
            pending.set(null);
            fired.set(false);
        }

        boolean fired() {
            return fired.get();
        }

    }

}
