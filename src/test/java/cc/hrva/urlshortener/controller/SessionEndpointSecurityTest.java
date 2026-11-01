package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.CurrentSession;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.impl.DefaultSessionService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SessionEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=" + SessionEndpointSecurityTest.JWT_SECRET,
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class SessionEndpointSecurityTest {

    static final String JWT_SECRET =
            "c2Vzc2lvbi1lbmRwb2ludC1zZWN1cml0eS10ZXN0LW9ubHktand0LXNlY3JldC1mb3ItdGhlLXJlYWwtdG9rZW4tcHJvdmlkZXItaHM1MTI=";

    private static final String END_SESSIONS = "/api/v1/users/me/sessions/end";
    private static final String EMAIL = "ana@hrva.cc";
    private static final String SESSION_ENDED = """
            {"error":"Your session ended. Sign in again."}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AccountLock accountLock;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    private User ana;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        final var role = new Authorities();
        role.setName("ROLE_USER");
        ana = User.builder().id(7L).email(EMAIL).active(true).authorities(List.of(role)).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(ana));
        when(accountLock.lock(any())).thenReturn(ana);
    }

    @Test
    void signingOutEverywhereKeepsThisDeviceWithANewTokenAndEndsEveryOlderOne() throws Exception {
        final var phone = tokenIssued(Duration.ofMinutes(30));
        final var laptop = tokenIssued(Duration.ofSeconds(5));

        final var answer = mockMvc.perform(post(END_SESSIONS).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + laptop))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        final var fresh = answer.getHeader(JwtFilter.AUTHORIZATION_HEADER);

        assertThat(fresh).startsWith("Bearer ");
        assertThat(answer.getContentAsString()).isEqualTo("{\"token\":\"" + fresh + "\"}");
        final var renewed = tokenProvider.read(fresh.substring(7)).orElseThrow();
        final var original = tokenProvider.read(laptop).orElseThrow();
        assertThat(renewed.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(renewed.signedInAt()).isEqualTo(original.signedInAt());

        for (final var ended : List.of(phone, laptop)) {
            mockMvc.perform(post(END_SESSIONS).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + ended))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json(SESSION_ENDED, JsonCompareMode.STRICT));
        }
        mockMvc.perform(post(END_SESSIONS).header(JwtFilter.AUTHORIZATION_HEADER, fresh))
                .andExpect(status().isOk());
    }

    @Test
    void signingOutEverywhereNeedsASignIn() throws Exception {
        mockMvc.perform(post(END_SESSIONS)).andExpect(status().isUnauthorized());
        assertThat(ana.getTokensValidAfter()).isNull();
    }

    @Test
    void theFrontendCanReadTheNewTokenAndTheRetryTime() throws Exception {
        mockMvc.perform(options(END_SESSIONS)
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
        mockMvc.perform(post(END_SESSIONS)
                        .header("Origin", "http://localhost:3000")
                        .header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + tokenIssued(Duration.ofMinutes(1))))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", "Authorization, RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset, Retry-After"));
    }

    private static String tokenIssued(final Duration ago) {
        final var issuedAt = Instant.now().minus(ago);
        return Jwts.builder()
                .subject(EMAIL)
                .claim("auth", "ROLE_USER")
                .claim("auth_time", issuedAt.getEpochSecond())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(9))))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET)), Jwts.SIG.HS512)
                .compact();
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
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            TokenProvider.class,
            CurrentSession.class,
            DefaultSessionService.class,
            SessionController.class
    })
    static class TestApplication {
    }

}
