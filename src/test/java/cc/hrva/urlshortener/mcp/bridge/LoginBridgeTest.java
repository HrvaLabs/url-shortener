package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.controller.UserController;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.mcp.McpAccessTokens;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(
        classes = LoginBridgeTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
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
class LoginBridgeTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String CONNECT_PAGE = "http://localhost:3000/connect?request=";
    private static final String CONTINUE_URL = ISSUER + "/oauth2/connect/continue?otc=";
    private static final String EMAIL = "user@localhost";
    private static final String CLIENT_NAME = "Claude";
    private static final String REGISTERED_REDIRECT_URI = McpAccessTokens.REDIRECT_URI;
    private static final String OTHER_PORT_REDIRECT_URI = "http://localhost:61111/callback";
    private static final String STATE = "state 1/2&x";
    private static final String ENCODED_STATE = "state%201%2F2%26x";
    private static final String REGISTRATION = """
            {"client_name":"Claude","redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none"}""";
    private static final String CODE_CHALLENGE = s256(McpAccessTokens.CODE_VERIFIER);
    private static final String EXPIRED = "{\"error\":\"expired\"}";
    private static final String ALREADY_APPROVED = "{\"error\":\"already_approved\"}";
    private static final Duration REQUEST_TIME_TO_LIVE = Duration.ofMinutes(10);
    private static final Duration CODE_TIME_TO_LIVE = Duration.ofSeconds(60);
    private static final Duration AUTHORIZED_SESSION_TIME_TO_LIVE = Duration.ofSeconds(60);
    private static final String VIEW_PATH = "/api/v1/connect/requests/{handle}";
    private static final String APPROVE_PATH = "/api/v1/connect/requests/{handle}/approve";
    private static final String DENY_PATH = "/api/v1/connect/requests/{handle}/deny";
    private static final String CURRENT_USER_PATH = "/api/v1/users/me";
    private static final String INFRASTRUCTURE_HOST = "redis.internal.example:6379";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    @Qualifier("mcpJwtDecoder")
    private JwtDecoder mcpJwtDecoder;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private RateLimiter rateLimiter;

    private final InMemoryRedis redis = new InMemoryRedis();
    private final List<String> locations = new ArrayList<>();
    private McpAccessTokens accessTokens;
    private String appJwt;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        redis.backing(redisTemplate);
        accessTokens = new McpAccessTokens(mockMvc);
        final var user = User.builder().email(EMAIL).active(true).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(user);
        appJwt = appJwt(EMAIL, "ROLE_USER");
    }

    @Test
    void unauthenticatedAuthorizeRedirectsToConnect() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);

        final var started = startAuthorization(clientId, REGISTERED_REDIRECT_URI);

        assertThat(started.handle()).matches("[A-Za-z0-9_-]{43}");
        assertThat(started.session().getAttribute(ConnectRedirectEntryPoint.HANDLE_ATTRIBUTE)).isEqualTo(started.handle());
        assertThat(started.session().getMaxInactiveInterval()).isEqualTo((int) REQUEST_TIME_TO_LIVE.toSeconds());
        assertThat(redis.timeToLiveOfKeyContaining(started.handle())).isEqualTo(REQUEST_TIME_TO_LIVE);
        assertThat(startAuthorization(clientId, REGISTERED_REDIRECT_URI).handle()).isNotEqualTo(started.handle());
    }

    @Test
    void invalidAuthorizeRequestsAreNeverSentToConnect() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);

        final var unknownClient = perform(authorizationRequest("unknown-client", REGISTERED_REDIRECT_URI));
        final var foreignRedirect = perform(authorizationRequest(clientId, "https://evil.example/callback"));
        final var missingPkce = perform(get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", REGISTERED_REDIRECT_URI)
                .queryParam("state", STATE));

        assertThat(unknownClient.getStatus()).isEqualTo(400);
        assertThat(unknownClient.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(foreignRedirect.getStatus()).isEqualTo(400);
        assertThat(foreignRedirect.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(missingPkce.getStatus()).isEqualTo(302);
        assertThat(missingPkce.getHeader(HttpHeaders.LOCATION))
                .startsWith(REGISTERED_REDIRECT_URI + "?error=invalid_request")
                .endsWith("state=" + ENCODED_STATE);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void requestViewReturnsClientName() throws Exception {
        final var started = startAuthorization(accessTokens.register(REGISTRATION), REGISTERED_REDIRECT_URI);

        final var view = perform(get("/api/v1/connect/requests/{handle}", started.handle()).header(HttpHeaders.AUTHORIZATION, bearer(appJwt)));
        final var anonymousView = perform(get("/api/v1/connect/requests/{handle}", started.handle()));

        assertThat(view.getStatus()).isEqualTo(200);
        assertThat(view.getContentAsString()).isEqualTo("{\"clientName\":\"" + CLIENT_NAME + "\"}");
        assertThat(anonymousView.getStatus()).isEqualTo(401);
    }

    @Test
    void expiredRequestIs410() throws Exception {
        final var started = startAuthorization(accessTokens.register(REGISTRATION), REGISTERED_REDIRECT_URI);
        redis.expireKeysContaining(started.handle());

        for (final var handle : List.of(started.handle(), "unknown-handle")) {
            assertExpired(perform(view(handle)));
            assertExpired(perform(approve(handle)));
            assertExpired(perform(deny(handle)));
        }
    }

    @Test
    void approveRequiresAppJwt() throws Exception {
        final var started = startAuthorization(accessTokens.register(REGISTRATION), REGISTERED_REDIRECT_URI);
        when(userRepository.findByEmail("inactive@localhost"))
                .thenReturn(Optional.of(User.builder().email("inactive@localhost").active(false).build()));

        assertThat(perform(post("/api/v1/connect/requests/{handle}/approve", started.handle())).getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/v1/connect/requests/{handle}/approve", started.handle())
                .header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt"))).getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/v1/connect/requests/{handle}/approve", started.handle())
                .header(HttpHeaders.AUTHORIZATION, bearer(appJwt("inactive@localhost", "ROLE_USER")))).getStatus()).isEqualTo(401);

        final var approval = perform(approve(started.handle()));
        assertThat(approval.getStatus()).isEqualTo(200);
        assertThat(continueUrl(approval)).startsWith(CONTINUE_URL);
    }

    @Test
    void adminOnlyAccountsCanUseConnectApis() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var approved = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var denied = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var adminOnly = bearer(appJwt(EMAIL, "ROLE_ADMIN"));
        final var neitherRole = bearer(appJwt(EMAIL, "ROLE_OTHER"));

        assertThat(perform(get(VIEW_PATH, approved.handle())).getStatus()).isEqualTo(401);
        assertThat(perform(post(APPROVE_PATH, approved.handle())).getStatus()).isEqualTo(401);
        assertThat(perform(post(DENY_PATH, denied.handle())).getStatus()).isEqualTo(401);
        assertThat(perform(get(VIEW_PATH, approved.handle()).header(HttpHeaders.AUTHORIZATION, neitherRole)).getStatus()).isEqualTo(403);
        assertThat(perform(post(APPROVE_PATH, approved.handle()).header(HttpHeaders.AUTHORIZATION, neitherRole)).getStatus()).isEqualTo(403);
        assertThat(perform(post(DENY_PATH, denied.handle()).header(HttpHeaders.AUTHORIZATION, neitherRole)).getStatus()).isEqualTo(403);

        final var view = perform(get(VIEW_PATH, approved.handle()).header(HttpHeaders.AUTHORIZATION, adminOnly));
        final var approval = perform(post(APPROVE_PATH, approved.handle()).header(HttpHeaders.AUTHORIZATION, adminOnly));
        final var denial = perform(post(DENY_PATH, denied.handle()).header(HttpHeaders.AUTHORIZATION, adminOnly));

        assertThat(view.getStatus()).isEqualTo(200);
        assertThat(view.getContentAsString()).isEqualTo("{\"clientName\":\"" + CLIENT_NAME + "\"}");
        assertThat(continueUrl(approval)).startsWith(CONTINUE_URL);
        assertThat(denial.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(denial.getContentAsString(), "$.redirectTo"))
                .isEqualTo(REGISTERED_REDIRECT_URI + "?error=access_denied&state=" + ENCODED_STATE);
    }

    @Test
    void approveTwiceIs409() throws Exception {
        final var started = startAuthorization(accessTokens.register(REGISTRATION), REGISTERED_REDIRECT_URI);

        final var firstApproval = perform(approve(started.handle()));
        final var secondApproval = perform(approve(started.handle()));
        final var denialAfterApproval = perform(deny(started.handle()));
        final var viewAfterApproval = perform(view(started.handle()));

        assertThat(firstApproval.getStatus()).isEqualTo(200);
        final var code = continueUrl(firstApproval).substring(CONTINUE_URL.length());
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(redis.timeToLiveOfKeyContaining(code)).isEqualTo(CODE_TIME_TO_LIVE);
        assertThat(secondApproval.getStatus()).isEqualTo(409);
        assertThat(secondApproval.getContentAsString()).isEqualTo(ALREADY_APPROVED);
        assertThat(denialAfterApproval.getStatus()).isEqualTo(409);
        assertThat(denialAfterApproval.getContentAsString()).isEqualTo(ALREADY_APPROVED);
        assertExpired(viewAfterApproval);
    }

    @Test
    void concurrentApprovesGiveOne200AndOne409() throws Exception {
        final var started = startAuthorization(accessTokens.register(REGISTRATION), REGISTERED_REDIRECT_URI);
        final var ready = new CountDownLatch(2);
        final var go = new CountDownLatch(1);

        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var statuses = new ArrayList<Future<Integer>>();
            for (var i = 0; i < 2; i++) {
                statuses.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(approve(started.handle())).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();

            assertThat(List.of(statuses.get(0).get(), statuses.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void denyRedirectsWithAccessDenied() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var started = startAuthorization(clientId, OTHER_PORT_REDIRECT_URI);
        final var withoutRedirectUri = startAuthorization(clientId, null);

        final var denial = perform(deny(started.handle()));
        final var denialWithoutRedirectUri = perform(deny(withoutRedirectUri.handle()));

        assertThat(denial.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(denial.getContentAsString(), "$.redirectTo"))
                .isEqualTo(OTHER_PORT_REDIRECT_URI + "?error=access_denied&state=" + ENCODED_STATE);
        assertThat(JsonPath.<String>read(denialWithoutRedirectUri.getContentAsString(), "$.redirectTo"))
                .isEqualTo(REGISTERED_REDIRECT_URI + "?error=access_denied&state=" + ENCODED_STATE);
        assertExpired(perform(view(started.handle())));
        assertExpired(perform(deny(started.handle())));
        assertExpired(perform(approve(started.handle())));
    }

    @Test
    void continueFromOtherBrowserIs403() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var started = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var otherBrowser = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var continueUrl = continueUrl(perform(approve(started.handle())));

        final var fromOtherBrowser = perform(get(URI.create(continueUrl)).session(otherBrowser.session()));

        assertThat(fromOtherBrowser.getStatus()).isEqualTo(403);
        assertThat(fromOtherBrowser.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(otherBrowser.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();

        final var withoutSession = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var secondContinueUrl = continueUrl(perform(approve(withoutSession.handle())));

        assertThat(perform(get(URI.create(secondContinueUrl))).getStatus()).isEqualTo(403);
    }

    @Test
    void replayedOtcIs400() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var started = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var continueUrl = continueUrl(perform(approve(started.handle())));

        assertThat(perform(get(URI.create(continueUrl)).session(started.session())).getStatus()).isEqualTo(302);
        assertThat(perform(get(URI.create(continueUrl)).session(started.session())).getStatus()).isEqualTo(400);

        final var expiring = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var expiringContinueUrl = continueUrl(perform(approve(expiring.handle())));
        redis.expireKeysContaining(expiringContinueUrl.substring(CONTINUE_URL.length()));

        assertThat(perform(get(URI.create(expiringContinueUrl)).session(expiring.session())).getStatus()).isEqualTo(400);
        assertThat(perform(get("/oauth2/connect/continue").session(expiring.session())).getStatus()).isEqualTo(400);
        assertThat(expiring.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Test
    void continuedSessionOnlyAuthorizesTheApprovedRequest() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var otherClientId = accessTokens.register(REGISTRATION);
        final var started = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var session = started.session();
        final var continued = perform(get(URI.create(continueUrl(perform(approve(started.handle()))))).session(session));
        assertThat(continued.getStatus()).isEqualTo(302);

        final var otherAuthorization = mockMvc.perform(authorizationRequest(otherClientId, REGISTERED_REDIRECT_URI).session(session)).andReturn();

        final var location = otherAuthorization.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(otherAuthorization.getResponse().getStatus()).isEqualTo(302);
        assertThat(location).startsWith(CONNECT_PAGE).doesNotContain("code=");
        assertThat(session.isInvalid()).isTrue();
        final var newSession = (MockHttpSession) otherAuthorization.getRequest().getSession(false);
        assertThat(newSession).isNotNull().isNotSameAs(session);
        assertThat(newSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(newSession.getAttribute(ConnectRedirectEntryPoint.HANDLE_ATTRIBUTE)).isEqualTo(location.substring(CONNECT_PAGE.length()));
        assertThat(redis.timeToLiveOfKeyContaining(location.substring(CONNECT_PAGE.length()))).isEqualTo(REQUEST_TIME_TO_LIVE);
    }

    @Test
    void authenticatedSessionWithoutApprovedQueryIsSentToConnect() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        final var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

        final var authorization = perform(authorizationRequest(clientId, REGISTERED_REDIRECT_URI).session(session));

        assertThat(authorization.getStatus()).isEqualTo(302);
        assertThat(authorization.getHeader(HttpHeaders.LOCATION)).startsWith(CONNECT_PAGE).doesNotContain("code=");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void fullFlowIssuesCodeForApprovingUserAndEndsSession(final CapturedOutput output) throws Exception {
        final var outputBeforeFlow = output.getAll().length();
        final var clientId = accessTokens.register(REGISTRATION);
        final var started = startAuthorization(clientId, OTHER_PORT_REDIRECT_URI);
        final var session = started.session();

        assertThat(perform(view(started.handle())).getStatus()).isEqualTo(200);
        final var continueUrl = continueUrl(perform(approve(started.handle())));
        final var sessionIdBeforeContinue = session.getId();

        final var continued = perform(get(URI.create(continueUrl)).session(session));

        assertThat(continued.getStatus()).isEqualTo(302);
        assertThat(continued.getHeader(HttpHeaders.LOCATION)).isEqualTo(ISSUER + "/oauth2/authorize?" + started.query());
        assertThat(session.getId()).isNotEqualTo(sessionIdBeforeContinue);
        assertThat(session.getMaxInactiveInterval()).isEqualTo((int) AUTHORIZED_SESSION_TIME_TO_LIVE.toSeconds());
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(ConnectRedirectEntryPoint.HANDLE_ATTRIBUTE)).isNull();
        final var securityContext = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext.getAuthentication().getName()).isEqualTo(EMAIL);

        final var authorized = perform(get(URI.create(continued.getHeader(HttpHeaders.LOCATION))).session(session));

        assertThat(authorized.getStatus()).isEqualTo(302);
        assertThat(session.isInvalid()).isTrue();
        final var callback = authorized.getHeader(HttpHeaders.LOCATION);
        assertThat(callback).startsWith(OTHER_PORT_REDIRECT_URI + "?code=").endsWith("&state=" + ENCODED_STATE);
        final var code = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");
        final var authorization = authorizationService.findByToken(code, new OAuth2TokenType(OAuth2ParameterNames.CODE));
        assertThat(authorization).isNotNull();
        assertThat(authorization.getPrincipalName()).isEqualTo(EMAIL);
        assertThat(authorization.<Authentication>getAttribute(Principal.class.getName()).getName()).isEqualTo(EMAIL);

        final var tokens = perform(post("/oauth2/token")
                .contentType(APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", OTHER_PORT_REDIRECT_URI)
                .param("code_verifier", McpAccessTokens.CODE_VERIFIER)
                .param("resource", RESOURCE));

        assertThat(tokens.getStatus()).isEqualTo(200);
        final String accessToken = JsonPath.read(tokens.getContentAsString(), "$.access_token");
        final String refreshToken = JsonPath.read(tokens.getContentAsString(), "$.refresh_token");
        assertThat(mcpJwtDecoder.decode(accessToken).getSubject()).isEqualTo(EMAIL);

        final var refreshed = perform(post("/oauth2/token")
                .contentType(APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE));

        assertThat(refreshed.getStatus()).isEqualTo(200);
        assertThat(mcpJwtDecoder.decode(JsonPath.read(refreshed.getContentAsString(), "$.access_token")).getSubject()).isEqualTo(EMAIL);
        assertThat(output.getAll().substring(outputBeforeFlow))
                .doesNotContain(started.handle())
                .doesNotContain(continueUrl.substring(CONTINUE_URL.length()))
                .doesNotContain(appJwt)
                .doesNotContain(CODE_CHALLENGE)
                .doesNotContain(ENCODED_STATE);
    }

    @Test
    void mcpAccessTokenIsNotAcceptedByTheApi() throws Exception {
        final var accessToken = accessTokens.issue(EMAIL, RESOURCE);
        when(userService.fetchCurrentUser()).thenReturn(UserDto.builder().email(EMAIL).build());

        assertThat(perform(get(CURRENT_USER_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).getStatus()).isEqualTo(401);
        assertThat(perform(get(CURRENT_USER_PATH).header(HttpHeaders.AUTHORIZATION, bearer(appJwt))).getStatus()).isEqualTo(200);
    }

    @Test
    void bridgedSessionDoesNotAuthenticateTheApi() throws Exception {
        final var started = startAuthorization(accessTokens.register(REGISTRATION), REGISTERED_REDIRECT_URI);
        final var session = started.session();
        final var continued = perform(get(URI.create(continueUrl(perform(approve(started.handle()))))).session(session));
        assertThat(continued.getStatus()).isEqualTo(302);
        final var securityContext = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext.getAuthentication().getName()).isEqualTo(EMAIL);

        final var currentUser = perform(get(CURRENT_USER_PATH).session(session));

        assertThat(currentUser.getStatus()).isEqualTo(401);
        verify(userService, never()).fetchCurrentUser();
    }

    @Test
    void jwtNeverAppearsInAnyLocationHeader() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var approved = startAuthorization(clientId, REGISTERED_REDIRECT_URI);
        final var denied = startAuthorization(clientId, REGISTERED_REDIRECT_URI);

        perform(view(approved.handle()));
        final var approval = perform(approve(approved.handle()));
        final var continued = perform(get(URI.create(continueUrl(approval))).session(approved.session()));
        perform(get(URI.create(continued.getHeader(HttpHeaders.LOCATION))).session(approved.session()));
        final var denial = perform(deny(denied.handle()));

        assertThat(locations).hasSizeGreaterThanOrEqualTo(4).allSatisfy(location -> assertThat(location).doesNotContain(appJwt));
        assertThat(approval.getContentAsString()).doesNotContain(appJwt);
        assertThat(denial.getContentAsString()).doesNotContain(appJwt);
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSiteLax() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var authorizeUri = authorizeUri(clientId);
        final var googleLoginUri = URI.create("http://localhost:" + port + "/oauth2/authorization/google");

        try (final var httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            final var authorize = httpClient.send(HttpRequest.newBuilder(authorizeUri).GET().build(), HttpResponse.BodyHandlers.discarding());
            final var googleLogin = httpClient.send(HttpRequest.newBuilder(googleLoginUri).GET().build(), HttpResponse.BodyHandlers.discarding());

            assertThat(authorize.statusCode()).isEqualTo(302);
            assertThat(authorize.headers().firstValue(HttpHeaders.LOCATION)).hasValueSatisfying(location -> assertThat(location).startsWith(CONNECT_PAGE));
            assertSessionCookieIsHttpOnlySecureAndLax(authorize);
            assertThat(googleLogin.statusCode()).isEqualTo(302);
            assertThat(googleLogin.headers().firstValue(HttpHeaders.LOCATION)).hasValueSatisfying(location -> assertThat(location).startsWith("https://accounts.google.com/"));
            assertSessionCookieIsHttpOnlySecureAndLax(googleLogin);
        }
    }

    @Test
    void redisFailureWhileStartingAConnectIs503WithoutInfrastructureDetails(final CapturedOutput output) throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis at " + INFRASTRUCTURE_HOST));

        try (final var httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            final var authorize = httpClient.send(HttpRequest.newBuilder(authorizeUri(clientId))
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(authorize.statusCode()).isEqualTo(503);
            assertThat(authorize.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
            assertThat(authorize.body()).doesNotContain(INFRASTRUCTURE_HOST);
        }
        assertThat(output.getAll()).doesNotContain(INFRASTRUCTURE_HOST);
    }

    private URI authorizeUri(final String clientId) {
        return UriComponentsBuilder.fromUriString("http://localhost:" + port + "/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", REGISTERED_REDIRECT_URI)
                .queryParam("code_challenge", CODE_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", STATE)
                .encode()
                .build()
                .toUri();
    }

    private static void assertSessionCookieIsHttpOnlySecureAndLax(final HttpResponse<?> response) {
        assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE))
                .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie.split(";\\s*")).contains("HttpOnly", "Secure", "SameSite=Lax"));
    }

    private StartedAuthorization startAuthorization(final String clientId, final String redirectUri) throws Exception {
        final var result = mockMvc.perform(authorizationRequest(clientId, redirectUri)).andReturn();
        final var location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(location).startsWith(CONNECT_PAGE);
        locations.add(location);

        return new StartedAuthorization(
                location.substring(CONNECT_PAGE.length()),
                (MockHttpSession) result.getRequest().getSession(false),
                result.getRequest().getQueryString()
        );
    }

    private static MockHttpServletRequestBuilder authorizationRequest(final String clientId, final String redirectUri) {
        final var request = get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("code_challenge", CODE_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", STATE)
                .queryParam("resource", RESOURCE);

        return redirectUri == null ? request : request.queryParam("redirect_uri", redirectUri);
    }

    private MockHttpServletRequestBuilder view(final String handle) {
        return get("/api/v1/connect/requests/{handle}", handle).header(HttpHeaders.AUTHORIZATION, bearer(appJwt));
    }

    private MockHttpServletRequestBuilder approve(final String handle) {
        return post("/api/v1/connect/requests/{handle}/approve", handle).header(HttpHeaders.AUTHORIZATION, bearer(appJwt));
    }

    private MockHttpServletRequestBuilder deny(final String handle) {
        return post("/api/v1/connect/requests/{handle}/deny", handle).header(HttpHeaders.AUTHORIZATION, bearer(appJwt));
    }

    private MockHttpServletResponse perform(final RequestBuilder request) throws Exception {
        final var response = mockMvc.perform(request).andReturn().getResponse();
        final var location = response.getHeader(HttpHeaders.LOCATION);
        if (location != null) {
            locations.add(location);
        }

        return response;
    }

    private static void assertExpired(final MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(response.getContentAsString()).isEqualTo(EXPIRED);
    }

    private static String continueUrl(final MockHttpServletResponse approval) throws Exception {
        assertThat(approval.getStatus()).isEqualTo(200);

        return JsonPath.read(approval.getContentAsString(), "$.continueUrl");
    }

    private String appJwt(final String email, final String authority) {
        return tokenProvider.createToken(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private static String bearer(final String token) {
        return "Bearer " + token;
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

    private record StartedAuthorization(String handle, MockHttpSession session, String query) {
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
            ClientIpResolver.class,
            TokenProvider.class,
            RedisConnectRequestStore.class,
            ConnectController.class,
            ConnectContinueController.class,
            UserController.class
    })
    static class TestApplication {
    }

}
