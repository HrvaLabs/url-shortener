package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.CreatedInRange;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.LinkInventory;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AdminStatsService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminStatsSecurityTest.TestApplication.class,
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
class AdminStatsSecurityTest {

    private static final String STATS = "/api/v1/admin/stats/";
    private static final String RANGE_ERROR = "days must be 7, 30, 90 or 365";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminStatsService adminStatsService;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

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

    @BeforeEach
    void setUp() {
        signedIn("admin-token", ADMIN, "ROLE_ADMIN");
        signedIn("user-token", USER, "ROLE_USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"traffic", "links"})
    void anAdminGetsTheTab(final String tab) throws Exception {
        respond(tab);

        mockMvc.perform(tabRequest(tab, "admin-token").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"traffic", "links"})
    void aSignedInUserWhoIsNotAnAdminIsForbidden(final String tab) throws Exception {
        mockMvc.perform(tabRequest(tab, "user-token").param("days", "7"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminStatsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"traffic", "links"})
    void nobodySignedInIsUnauthorized(final String tab) throws Exception {
        mockMvc.perform(get(STATS + tab).param("days", "7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminStatsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"traffic", "links"})
    void anUnsupportedRangeIsABadRequest(final String tab) throws Exception {
        refuseRange(tab);

        mockMvc.perform(tabRequest(tab, "admin-token").param("days", "14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(RANGE_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"traffic", "links"})
    void aMistypedRangeIsABadRequest(final String tab) throws Exception {
        mockMvc.perform(tabRequest(tab, "admin-token").param("days", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for days"));

        verifyNoInteractions(adminStatsService);
    }

    private void respond(final String tab) {
        switch (tab) {
            case "traffic" -> when(adminStatsService.traffic(7)).thenReturn(new TrafficStatsResponse(7, LocalDate.of(2026, 10, 20),
                    LocalDate.of(2026, 10, 26), new AnalyticsTotals(0, 0, 0), List.of(), 0, 0, List.of(), emptyBreakdowns(), 0));
            case "links" -> when(adminStatsService.links(7)).thenReturn(new LinksStatsResponse(7, LocalDate.of(2026, 10, 20),
                    LocalDate.of(2026, 10, 26), new StackedSeries(List.of(), List.of()), new CreatedInRange(0, 0, 0, 0),
                    new LinkInventory(0, 0, 0, 0, 0, 0, 0, List.of()), List.of(), List.of(), List.of()));
            default -> throw new IllegalArgumentException("No response for the " + tab + " tab");
        }
    }

    private void refuseRange(final String tab) {
        final var refusal = new InvalidAnalyticsRangeException(RANGE_ERROR);
        switch (tab) {
            case "traffic" -> when(adminStatsService.traffic(14)).thenThrow(refusal);
            case "links" -> when(adminStatsService.links(14)).thenThrow(refusal);
            default -> throw new IllegalArgumentException("No refusal for the " + tab + " tab");
        }
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static Breakdowns emptyBreakdowns() {
        return new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static MockHttpServletRequestBuilder tabRequest(final String tab, final String token) {
        return get(STATS + tab).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token);
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
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            AdminStatsController.class
    })
    static class TestApplication {
    }

}
