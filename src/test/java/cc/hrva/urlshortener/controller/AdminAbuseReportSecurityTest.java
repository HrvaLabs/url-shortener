package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.abuse.AbuseReportRequest;
import cc.hrva.urlshortener.dto.abuse.AbuseReportResponse;
import cc.hrva.urlshortener.dto.abuse.ReportedDomainsDto;
import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import cc.hrva.urlshortener.exception.CampaignNotFoundException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AbuseReportService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminAbuseReportSecurityTest.TestApplication.class,
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
class AdminAbuseReportSecurityTest {

    private static final String PREPARE = "/api/v1/admin/abuse-reports";
    private static final String REPORTED = PREPARE + "/reported";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final String MARK_BODY = "{\"domains\":[\"omaehhm.com\",\"buokpky.com\"],\"registrar\":\"NameSilo, LLC\",\"abuseEmail\":\"abuse@namesilo.com\"}";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AbuseReportService abuseReportService;

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

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        signedIn("admin-token", ADMIN, "ROLE_ADMIN");
        signedIn("user-token", USER, "ROLE_USER");
    }

    @Test
    void anAdminPreparesTheReportsInTheContractsShape() throws Exception {
        final var report = new ReportStatus(LocalDateTime.of(2026, 10, 28, 7, 0), "karlo@hrva.cc", "NameSilo, LLC", null);
        when(abuseReportService.prepare(new AbuseReportRequest(List.of(41L, 42L), null))).thenReturn(new AbuseReportResponse(
                List.of(new AbuseReportResponse.Report("NameSilo, LLC", "abuse@namesilo.com", List.of("reportphishing@apwg.org"),
                        "Phishing: 2 domains used in links to wwkn0gb.bgp37.sbs", "Hello,\n\nI run hrva.cc",
                        List.of(new AbuseReportResponse.Domain("omaehhm.com", LocalDate.of(2026, 8, 14), report),
                                new AbuseReportResponse.Domain("buokpky.com", null, null)))),
                List.of(new AbuseReportResponse.NoContact("example.hr", null, false, null),
                        new AbuseReportResponse.NoContact("slow.com", "Some Registrar", true, null))));

        mockMvc.perform(signedIn(post(PREPARE), "admin-token").content("{\"urlIds\":[41,42]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"reports":[{"registrar":"NameSilo, LLC","to":"abuse@namesilo.com","cc":["reportphishing@apwg.org"],
                          "subject":"Phishing: 2 domains used in links to wwkn0gb.bgp37.sbs","body":"Hello,\\n\\nI run hrva.cc",
                          "domains":[{"domain":"omaehhm.com","registeredOn":"2026-08-14",
                                      "report":{"reportedAt":"2026-10-28T07:00:00","reportedBy":"karlo@hrva.cc","registrar":"NameSilo, LLC","suspendedAt":null}},
                                     {"domain":"buokpky.com","registeredOn":null,"report":null}]}],
                         "noContact":[{"domain":"example.hr","registrar":null,"lookupFailed":false,"report":null},
                                      {"domain":"slow.com","registrar":"Some Registrar","lookupFailed":true,"report":null}]}""", JsonCompareMode.STRICT));
    }

    @Test
    void anAdminPreparesACampaignsReports() throws Exception {
        when(abuseReportService.prepare(new AbuseReportRequest(null, 4L))).thenReturn(new AbuseReportResponse(List.of(), List.of()));

        mockMvc.perform(signedIn(post(PREPARE), "admin-token").content("{\"campaignId\":4}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"reports\":[],\"noContact\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void theLinkListIsRefusedWithPartFsTexts() throws Exception {
        final var tooMany = LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
        for (final var body : List.of("{\"urlIds\":[" + tooMany + "]}", "{\"urlIds\":[]}")) {
            mockMvc.perform(signedIn(post(PREPARE), "admin-token").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"urlIds\":\"Select between 1 and 100 links\"}", JsonCompareMode.STRICT));
        }
        mockMvc.perform(signedIn(post(PREPARE), "admin-token").content("{\"urlIds\":[1,1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"urlIds\":\"Select each link only once\"}", JsonCompareMode.STRICT));
        mockMvc.perform(signedIn(post(PREPARE), "admin-token").content("{\"urlIds\":[1,null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"urlIds[1]\":\"Every selected link needs its id\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(abuseReportService);
    }

    @Test
    void bothOrNeitherSourceIsRefused() throws Exception {
        when(abuseReportService.prepare(any())).thenThrow(new FieldValidationException("urlIds", "Select between 1 and 100 links"));

        mockMvc.perform(signedIn(post(PREPARE), "admin-token").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"urlIds\":\"Select between 1 and 100 links\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anUnknownCampaignIsNotFound() throws Exception {
        when(abuseReportService.prepare(new AbuseReportRequest(null, 99L))).thenThrow(new CampaignNotFoundException("There is no campaign 99."));

        mockMvc.perform(signedIn(post(PREPARE), "admin-token").content("{\"campaignId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"There is no campaign 99.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anAdminMarksDomainsReportedAsThemselves() throws Exception {
        mockMvc.perform(signedIn(post(REPORTED), "admin-token").content(MARK_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(abuseReportService).markReported(new ReportedDomainsDto(List.of("omaehhm.com", "buokpky.com"), "NameSilo, LLC", "abuse@namesilo.com"), ADMIN);
    }

    @Test
    void markingTakesOneTo100Domains() throws Exception {
        final var tooMany = LongStream.rangeClosed(1, 101).mapToObj(index -> "\"d" + index + ".com\"").collect(Collectors.joining(","));
        for (final var body : List.of("{\"domains\":[" + tooMany + "],\"registrar\":null,\"abuseEmail\":null}", "{\"domains\":[]}", "{}")) {
            mockMvc.perform(signedIn(post(REPORTED), "admin-token").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"domains\":\"Select between 1 and 100 domains\"}", JsonCompareMode.STRICT));
        }
        verifyNoInteractions(abuseReportService);
    }

    @Test
    void anAbuseAddressLongerThanAnEmailAddressCanBeIsRefused() throws Exception {
        final var body = "{\"domains\":[\"omaehhm.com\"],\"registrar\":\"" + "R".repeat(256) + "\",\"abuseEmail\":\"" + "a".repeat(250) + "@x.hr\"}";

        mockMvc.perform(signedIn(post(REPORTED), "admin-token").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"registrar\":\"Use at most 255 characters for the registrar\","
                        + "\"abuseEmail\":\"Use at most 254 characters for the abuse address\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(abuseReportService);
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        mockMvc.perform(signedIn(post(PREPARE), "user-token").content("{\"urlIds\":[1]}")).andExpect(status().isForbidden());
        mockMvc.perform(signedIn(post(REPORTED), "user-token").content(MARK_BODY)).andExpect(status().isForbidden());

        verifyNoInteractions(abuseReportService);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        mockMvc.perform(post(PREPARE).contentType(MediaType.APPLICATION_JSON).content("{\"urlIds\":[1]}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(REPORTED).contentType(MediaType.APPLICATION_JSON).content(MARK_BODY)).andExpect(status().isUnauthorized());

        verifyNoInteractions(abuseReportService);
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static MockHttpServletRequestBuilder signedIn(final MockHttpServletRequestBuilder request, final String token) {
        return request.header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
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
            AdminAbuseReportController.class
    })
    static class TestApplication {
    }

}
