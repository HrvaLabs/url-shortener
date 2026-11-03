package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.client.VirusTotalClient;
import cc.hrva.urlshortener.client.VirusTotalReport;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.SessionToken;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.inspection.DefaultLinkInspectionService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminLinkInspectionSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:admin-link-inspection;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc"
        })
@AutoConfigureMockMvc
class AdminLinkInspectionSecurityTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final long ADMIN = 900L;
    private static final long ANA = 910L;
    private static final long LINK = 9401L;
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final String ANA_EMAIL = "ana@example.com";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final String INSPECTION = "/api/v1/admin/urls/" + LINK + "/inspection";
    private static final String SCREENSHOT = INSPECTION + "/screenshot";
    private static final String VIRUSTOTAL = INSPECTION + "/virustotal";
    private static final String LONG_URL = "https://findfavoritedate.life/?u=f6yp608";
    private static final String REPORT_ID = "1db0ad7dbcec0676710ea0eaacd35d5e471d3e11944d53bcbd31f0cbd11bce31";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private SettingsService settingsService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private VirusTotalClient virusTotalClient;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        account(ADMIN, ADMIN_EMAIL, ROLE_USER, ROLE_ADMIN);
        jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", ADMIN);
        account(ANA, ANA_EMAIL, ROLE_USER);
        signedIn(ADMIN_TOKEN, ADMIN_EMAIL, "ROLE_ADMIN");
        signedIn(USER_TOKEN, ANA_EMAIL, "ROLE_USER");
        when(userService.getUserFromToken()).thenReturn(User.builder().id(ADMIN).email(ADMIN_EMAIL).build());
        jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits, redirect_chain) values (?, 'v0DB3', ?, true, 0, ?)", LINK, LONG_URL,
                LONG_URL + "\nhttps://sso.findfavoritedate.life/");
        appProperties.getCloudflare().setAccountId("account-1");
        appProperties.getCloudflare().setBrowserApiToken("token-1");
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.LINK_SCREENSHOTS);
        appProperties.getInspection().setVirusTotalApiKey("vt-key-1");
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.VIRUSTOTAL_LOOKUPS);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from link_screenshot");
        jdbcTemplate.update("delete from link_inspection");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from user_account_authorities");
        jdbcTemplate.update("delete from user_account");
        appProperties.getCloudflare().setAccountId(null);
        appProperties.getCloudflare().setBrowserApiToken(null);
        appProperties.getInspection().setVirusTotalApiKey(null);
    }

    @Test
    void aNonAdminGets403OnEveryRouteAndNobodySignedIn401() throws Exception {
        done();
        final var requests = List.of(get(INSPECTION), get(SCREENSHOT), post(SCREENSHOT), post(VIRUSTOTAL));

        for (final var request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
            mockMvc.perform(as(USER_TOKEN, request)).andExpect(status().isForbidden());
        }

        assertThat(jdbcTemplate.queryForObject("select screenshot_state from link_inspection where url_id = ?", String.class, LINK)).isEqualTo("DONE");
        assertThat(count("audit_log")).isZero();
        verify(virusTotalClient, never()).submit(anyString());
    }

    @Test
    void anAdminReadsTheInspection() throws Exception {
        done();

        mockMvc.perform(as(ADMIN_TOKEN, get(INSPECTION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urlId").value(LINK))
                .andExpect(jsonPath("$.longUrl").value(LONG_URL))
                .andExpect(jsonPath("$.redirectChain[1]").value("https://sso.findfavoritedate.life/"))
                .andExpect(jsonPath("$.screenshot.state").value("DONE"))
                .andExpect(jsonPath("$.screenshot.takenAt").value("2026-11-03T09:59:00Z"))
                .andExpect(jsonPath("$.screenshot.pageTitle").value("Find your date"))
                .andExpect(jsonPath("$.screenshot.pageStatus").value(200))
                .andExpect(jsonPath("$.screenshot.configured").value(true))
                .andExpect(jsonPath("$.virusTotal.state").value("DONE"))
                .andExpect(jsonPath("$.virusTotal.checkedUrl").value(LONG_URL))
                .andExpect(jsonPath("$.virusTotal.malicious").value(7))
                .andExpect(jsonPath("$.virusTotal.suspicious").value(2))
                .andExpect(jsonPath("$.virusTotal.harmless").value(61))
                .andExpect(jsonPath("$.virusTotal.undetected").value(25))
                .andExpect(jsonPath("$.virusTotal.analysedAt").value("2026-11-02T18:30:00Z"))
                .andExpect(jsonPath("$.virusTotal.checkedAt").value("2026-11-03T09:59:00Z"))
                .andExpect(jsonPath("$.virusTotal.reportUrl").value("https://www.virustotal.com/gui/url/" + REPORT_ID))
                .andExpect(jsonPath("$.virusTotal.configured").value(true));
    }

    @Test
    void aLinkWithoutAScreenshotAnswersAnEmptyView() throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, get(INSPECTION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenshot.state").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.screenshot.configured").value(true));
        mockMvc.perform(as(ADMIN_TOKEN, get(SCREENSHOT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownLinkIsNotFound() throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, get("/api/v1/admin/urls/9999/inspection")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Link not found."));
        mockMvc.perform(as(ADMIN_TOKEN, post("/api/v1/admin/urls/9999/inspection/screenshot")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Link not found."));
    }

    @Test
    void theScreenshotIsServedWithItsContentType() throws Exception {
        done();

        mockMvc.perform(as(ADMIN_TOKEN, get(SCREENSHOT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"))
                .andExpect(content().bytes(JPEG));
    }

    @Test
    void takeAgainQueuesAndAudits() throws Exception {
        done();

        mockMvc.perform(as(ADMIN_TOKEN, post(SCREENSHOT)))
                .andExpect(status().isAccepted());

        assertThat(jdbcTemplate.queryForMap("select screenshot_state, screenshot_requested_by from link_inspection where url_id = ?", LINK))
                .containsEntry("SCREENSHOT_STATE", "PENDING")
                .containsEntry("SCREENSHOT_REQUESTED_BY", "ADMIN");
        assertThat(auditRows()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("ACTION", "TAKE_SCREENSHOT")
                .containsEntry("PERFORMED_BY", ADMIN_EMAIL)
                .containsEntry("TARGET_TYPE", "Url")
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(LINK))
                .containsEntry("FAILED", false)
                .containsEntry("DETAILS", "link: hrva.cc/v0DB3, destination: " + LONG_URL));
    }

    @Test
    void notConfiguredAnswers409() throws Exception {
        appProperties.getCloudflare().setBrowserApiToken(" ");

        mockMvc.perform(as(ADMIN_TOKEN, post(SCREENSHOT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Screenshots aren't set up."));

        appProperties.getCloudflare().setBrowserApiToken("token-1");
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.LINK_SCREENSHOTS);
        mockMvc.perform(as(ADMIN_TOKEN, post(SCREENSHOT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Screenshots aren't set up."));

        assertThat(count("link_inspection")).isZero();
        assertThat(auditRows()).extracting(audit -> audit.get("ACTION"), audit -> audit.get("FAILED"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("TAKE_SCREENSHOT", true), org.assertj.core.groups.Tuple.tuple("TAKE_SCREENSHOT", true));
    }

    @Test
    void submitAudits() throws Exception {
        when(virusTotalClient.submit(LONG_URL)).thenReturn(VirusTotalReport.submitted());

        mockMvc.perform(as(ADMIN_TOKEN, post(VIRUSTOTAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.submittedAt").value("2026-11-03T10:00:00Z"))
                .andExpect(jsonPath("$.configured").value(true));

        assertThat(jdbcTemplate.queryForMap("select vt_state, vt_requested_at from link_inspection where url_id = ?", LINK))
                .containsEntry("VT_STATE", "PENDING")
                .containsEntry("VT_REQUESTED_AT", java.sql.Timestamp.valueOf(NOW.plusSeconds(60)));
        assertThat(auditRows()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("ACTION", "SUBMIT_TO_VIRUSTOTAL")
                .containsEntry("PERFORMED_BY", ADMIN_EMAIL)
                .containsEntry("TARGET_TYPE", "Url")
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(LINK))
                .containsEntry("FAILED", false)
                .containsEntry("DETAILS", "link: hrva.cc/v0DB3, destination: " + LONG_URL));
    }

    @Test
    void submitAtTheLimitAnswers409() throws Exception {
        when(rateLimiter.increment(anyString(), any())).thenReturn(481L);

        mockMvc.perform(as(ADMIN_TOKEN, post(VIRUSTOTAL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Today's VirusTotal lookups are used up. Try again after midnight UTC."));

        verify(virusTotalClient, never()).submit(anyString());
        assertThat(auditRows()).extracting(audit -> audit.get("ACTION"), audit -> audit.get("FAILED"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SUBMIT_TO_VIRUSTOTAL", true));
    }

    @Test
    void submitWithoutVirusTotalAnswers409() throws Exception {
        appProperties.getInspection().setVirusTotalApiKey("");

        mockMvc.perform(as(ADMIN_TOKEN, post(VIRUSTOTAL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("VirusTotal isn't set up."));
        mockMvc.perform(as(ADMIN_TOKEN, post("/api/v1/admin/urls/9999/inspection/virustotal")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Link not found."));

        verify(virusTotalClient, never()).submit(anyString());
        assertThat(count("link_inspection")).isZero();
    }

    private void done() {
        jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, screenshot_requested_by, screenshot_taken_at, "
                + "screenshot_url, page_title, page_status, vt_state, vt_requested_at, vt_checked_at, vt_url, vt_report_id, vt_malicious, vt_suspicious, "
                + "vt_harmless, vt_undetected, vt_analysed_at) values (?, 'DONE', ?, 'QUEUE', ?, ?, 'Find your date', 200, 'DONE', ?, ?, ?, ?, 7, 2, 61, 25, ?)",
                LINK, NOW.minusMinutes(2), NOW.minusMinutes(1), LONG_URL, NOW.minusMinutes(2), NOW.minusMinutes(1), LONG_URL, REPORT_ID,
                LocalDateTime.of(2026, 11, 2, 18, 30));
        jdbcTemplate.update("insert into link_screenshot (url_id, image, content_type) values (?, ?, 'image/jpeg')", LINK, JPEG);
    }

    private int count(final String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private List<Map<String, Object>> auditRows() {
        return jdbcTemplate.queryForList("select action, performed_by, target_type, target_identifier, details, failed from audit_log order by id");
    }

    private void account(final long id, final String email, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, ?, 'secret-hash', true, ?)", id, email,
                NOW.minusYears(1));
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
    }

    private void signedIn(final String token, final String email, final String role) {
        when(tokenProvider.read(token))
                .thenReturn(Optional.of(new SessionToken(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))), null, null, null)));
    }

    private static MockHttpServletRequestBuilder as(final String token, final MockHttpServletRequestBuilder request) {
        return request.header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token);
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
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, UrlRepository.class, AuditLogRepository.class, CustomDomainRepository.class,
                            CustomDomainRequestRepository.class, LinkInspectionRepository.class, LinkScreenshotRepository.class}))
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
            AdminAuditAspect.class,
            AuditSubjects.class,
            ShortLinks.class,
            DefaultAuditLogService.class,
            DefaultSettingsTestConfiguration.class,
            DefaultLinkInspectionService.class,
            AdminLinkInspectionController.class,
            TestApplication.FixedClock.class
    })
    static class TestApplication {

        @TestConfiguration
        static class FixedClock {

            @Bean
            Clock clock() {
                return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
            }

        }

    }

}
