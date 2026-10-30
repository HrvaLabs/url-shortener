package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.AdminDomainResponses;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainAdminService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminCustomDomainSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:admin-custom-domains;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc"
        })
@AutoConfigureMockMvc
class AdminCustomDomainSecurityTest {

    private static final String DOMAINS = "/api/v1/admin/domains";
    private static final String ALLOWANCES = DOMAINS + "/allowances/";
    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final long ADMIN = 900L;
    private static final long ANA = 910L;
    private static final long BOB = 911L;
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final String ANA_EMAIL = "ana@example.com";
    private static final String BOB_EMAIL = "bob@example.org";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final long FIRST_EXTRA_OWNER = 1000L;
    private static final String ALREADY_REVIEWED = "{\"error\":\"This application was already reviewed.\"}";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private CloudflareTokenCheck cloudflareTokenCheck;

    @MockitoBean
    private CustomDomainProvisioner customDomainProvisioner;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        account(ADMIN, ADMIN_EMAIL, NOW.minusYears(1), ROLE_USER, ROLE_ADMIN);
        account(ANA, ANA_EMAIL, NOW.minusDays(40), ROLE_USER);
        account(BOB, BOB_EMAIL, NOW.minusDays(3), ROLE_USER);
        signedIn(ADMIN_TOKEN, ADMIN_EMAIL, "ROLE_ADMIN");
        signedIn(USER_TOKEN, ANA_EMAIL, "ROLE_USER");
        when(userService.getUserFromToken()).thenReturn(User.builder().id(ADMIN).email(ADMIN_EMAIL).build());
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 4));
        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW.minusMinutes(5), null, 4));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from link_review");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from custom_domain_allowance");
        jdbcTemplate.update("delete from custom_domain_reconcile");
        jdbcTemplate.update("delete from user_account_authorities");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void everyEndpointIsForAdminsOnly() throws Exception {
        final var id = domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));
        final var requests = List.of(
                get(DOMAINS),
                get(DOMAINS + "/summary"),
                post(DOMAINS + "/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"note\":null}"),
                post(DOMAINS + "/" + id + "/reject").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"No\"}"),
                post(DOMAINS + "/cloudflare-check"),
                get(ALLOWANCES + ANA),
                put(ALLOWANCES + ANA).contentType(MediaType.APPLICATION_JSON).content("{\"maxDomains\":3,\"reason\":\"Agency\"}"),
                delete(ALLOWANCES + ANA));

        for (final var request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
            mockMvc.perform(as(USER_TOKEN, request)).andExpect(status().isForbidden());
        }

        assertThat(statusOf(id)).isEqualTo("PENDING_REVIEW");
        assertThat(count("custom_domain_allowance")).isZero();
        assertThat(count("audit_log")).isZero();
        verifyNoInteractions(cloudflareTokenCheck, customDomainProvisioner);
    }

    @Test
    void anAdminReachesEveryEndpoint() throws Exception {
        final var approving = domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));
        final var rejecting = domain(BOB, "links.example.org", "PENDING_REVIEW", NOW.minusDays(1));

        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS))).andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS + "/summary"))).andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + approving + "/approve")).contentType(MediaType.APPLICATION_JSON).content("{\"note\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + rejecting + "/reject")).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"No\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/cloudflare-check"))).andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, get(ALLOWANCES + ANA))).andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + ANA)).contentType(MediaType.APPLICATION_JSON).content("{\"maxDomains\":3,\"reason\":\"Agency\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, delete(ALLOWANCES + ANA))).andExpect(status().isNoContent());
    }

    @Test
    void theListShowsEachDomainFlatWithItsOwner() throws Exception {
        final var id = activeDomainWithLinks();
        when(scammerSignalService.possibleScammers(anyCollection())).thenReturn(Set.of(ANA));

        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"id":%d,"hostname":"go.example.com","status":"ACTIVE","reason":"Menu links for my cafe","reviewNote":"Looks fine",
                        "createdAt":"2026-10-27T09:00:00","reviewedAt":"2026-10-28T09:00:00","activatedAt":"2026-10-28T09:30:00",
                        "lastCheckedAt":"2026-10-29T08:00:00",
                        "dnsRecord":{"type":"CNAME","name":"go.example.com","target":"domains.hrva.cc"},
                        "steps":{"dnsFound":true,"certificateIssued":true},"problem":null,"problemText":null,"disabledNote":null,"links":2,
                        "owner":{"id":%d,"email":"ana@example.com","active":true,"createdAt":"2026-09-19T09:00:00","links":3,"blockedLinks":1,
                        "openReviews":1,"possibleScammer":true},
                        "applyFlags":["Registered 3 days ago"],"cloudflareId":"b0361ed9-0000-4000-8000-000000000001","cloudflareStatus":"active",
                        "sslStatus":"active","lastError":null,"disabledReason":null,"disabledAt":null,"deletedAt":null,"clicks":1204}],
                        "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}
                        """.formatted(id, ANA), JsonCompareMode.STRICT));
    }

    @Test
    void theListIsNewestFirstAndFiltersByStatusAndSearch() throws Exception {
        final var older = domain(ANA, "go.example.com", "ACTIVE", NOW.minusDays(5));
        final var newer = domain(BOB, "links.example.org", "PENDING_REVIEW", NOW.minusDays(1));
        final var ownerless = domain(null, "left.example.net", "DELETED", NOW.minusDays(3));
        final var percent = domain(BOB, "percent50.example.org", "PENDING_REVIEW", NOW.minusDays(2));

        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS)))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) newer, (int) percent, (int) ownerless, (int) older)));
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("status", "PENDING_REVIEW").param("size", "1")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) newer)))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("search", " GO.Example ")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) older)));
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("search", "BOB@example")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) newer, (int) percent)));
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("search", "left.example")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) ownerless)))
                .andExpect(jsonPath("$.content[0].owner").doesNotExist());
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("search", "%")))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("search", "t5_")))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("status", "SOMETHING")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theListRunsTheSameStatementsForOneRowAndTwenty() throws Exception {
        activeDomainWithLinks();
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        final var forOne = statementsFor(statistics);
        IntStream.range(0, 19).forEach(index -> {
            final var owner = FIRST_EXTRA_OWNER + index;
            account(owner, "owner" + index + "@example.com", NOW.minusDays(10), ROLE_USER);
            final var id = domain(owner, "d" + index + ".example.com", "ACTIVE", NOW.minusHours(index + 1));
            link(owner, id, "d" + index, 3, null);
            final var blocked = link(owner, null, "b" + index, 0, "BLOCKLIST");
            review(blocked);
        });
        final var forTwenty = statementsFor(statistics);

        assertThat(forTwenty.rows()).isEqualTo(20);
        assertThat(forOne.rows()).isEqualTo(1);
        assertThat(forTwenty.statements()).isEqualTo(forOne.statements());
    }

    @Test
    void theSummaryShowsSlotsTotalsTheTokenAndTheLastReconcile() throws Exception {
        domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));
        final var active = domain(BOB, "links.example.org", "ACTIVE", NOW.minusDays(2));
        jdbcTemplate.update("update custom_domain set cloudflare_id = 'b0361ed9-0000-4000-8000-000000000009' where id = ?", active);
        reconciled();

        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS + "/summary")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"slotsInUse":1,"maxActive":90,"freeAllowance":100,"cloudflareTotal":4,"cloudflareQuota":null,"pending":1,"active":1,
                        "token":{"status":"OK","checkedAt":"2026-10-29T08:55:00","message":null},
                        "reconcile":{"ranAt":"2026-10-29T03:40:00","cloudflareTotal":3,"oursInCloudflare":2,"unknownHostnames":["stray.example.com"],
                        "missing":[{"id":9,"hostname":"x.example.org"}],"ignored":["test.karlo.cc"],"error":null}}
                        """, JsonCompareMode.STRICT));
        verify(cloudflareTokenCheck, never()).check();
    }

    @Test
    void theSummaryBeforeAnyCheckShowsNothingRead() throws Exception {
        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.UNREACHABLE, null, "Cloudflare couldn't be reached. We'll check again.", null));

        mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS + "/summary")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"slotsInUse":0,"maxActive":90,"freeAllowance":100,"cloudflareTotal":null,"cloudflareQuota":null,"pending":0,"active":0,
                        "token":{"status":"UNREACHABLE","checkedAt":null,"message":"Cloudflare couldn't be reached. We'll check again."},
                        "reconcile":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void anAdminApprovesAndTheAuditRowSaysSo() throws Exception {
        final var id = domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));

        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  Looks fine  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("APPROVED_PROVISIONING"))
                .andExpect(jsonPath("$.reviewNote").value("Looks fine"))
                .andExpect(jsonPath("$.reviewedAt").value("2026-10-29T09:00:00"))
                .andExpect(jsonPath("$.dnsRecord.target").value("domains.hrva.cc"))
                .andExpect(jsonPath("$.owner.email").value(ANA_EMAIL));

        final var row = jdbcTemplate.queryForMap("select status, reviewed_by, review_note, next_check_at from custom_domain where id = ?", id);
        assertThat(row).containsEntry("status", "APPROVED_PROVISIONING").containsEntry("reviewed_by", ADMIN_EMAIL)
                .containsEntry("review_note", "Looks fine");
        assertThat(row.get("next_check_at")).isNotNull();
        assertThat(auditRows()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("action", "APPROVE_DOMAIN")
                .containsEntry("performed_by", ADMIN_EMAIL)
                .containsEntry("target_type", "CustomDomain")
                .containsEntry("target_identifier", String.valueOf(id))
                .containsEntry("details", "note: Looks fine")
                .containsEntry("failed", false));
        verify(customDomainProvisioner).provisionAsync(id);
    }

    @Test
    void anApprovalCanCarryNoBody() throws Exception {
        final var id = domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));

        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/approve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewNote").doesNotExist());

        assertThat(statusOf(id)).isEqualTo("APPROVED_PROVISIONING");
    }

    @Test
    void refusedApprovalsAnswerWithTheContractTexts() throws Exception {
        final var id = domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));
        final var reviewed = domain(BOB, "links.example.org", "ACTIVE", NOW.minusDays(1));

        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.MISSING, NOW, "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.", null));
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"Cloudflare isn't set up: the API token is missing. Check CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID.\"}",
                        JsonCompareMode.STRICT));

        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 100));
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"Cloudflare already has 100 custom hostnames and 100 are free. Delete one here or in Cloudflare first.\"}",
                        JsonCompareMode.STRICT));

        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 4));
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + reviewed + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json(ALREADY_REVIEWED, JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + reviewed + "/reject")))
                .andExpect(status().isConflict())
                .andExpect(content().json(ALREADY_REVIEWED, JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/987654/approve")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"Custom domain not found\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/987654/reject")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"note\":\"Keep it to 500 characters or fewer\"}", JsonCompareMode.STRICT));

        jdbcTemplate.update("update user_account set active = false where id = ?", ANA);
        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"This account is deactivated, so its domain can't be approved.\"}", JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("PENDING_REVIEW");
        assertThat(auditRows()).extracting(audit -> audit.get("action")).containsOnly("APPROVE_DOMAIN", "REJECT_DOMAIN");
        assertThat(auditRows()).allSatisfy(audit -> assertThat(audit).containsEntry("failed", true));
        verify(customDomainProvisioner, never()).provisionAsync(anyLong());
    }

    @Test
    void anAdminRejectsAndTheHostnameIsFree() throws Exception {
        final var id = domain(ANA, "go.example.com", "PENDING_REVIEW", NOW.minusDays(1));

        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/" + id + "/reject")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"We can't verify this domain is yours\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("We can't verify this domain is yours"))
                .andExpect(jsonPath("$.dnsRecord").doesNotExist());

        final var row = jdbcTemplate.queryForMap("select status, hostname_key, reviewed_by from custom_domain where id = ?", id);
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("reviewed_by", ADMIN_EMAIL);
        assertThat(row.get("hostname_key")).isNull();
        assertThat(auditRows()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("action", "REJECT_DOMAIN")
                .containsEntry("target_identifier", String.valueOf(id))
                .containsEntry("failed", false));
        verifyNoInteractions(cloudflareTokenCheck, customDomainProvisioner);
    }

    @Test
    void checkingCloudflareAnswersTheFreshCheck() throws Exception {
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.INVALID, NOW,
                "Cloudflare refused the token. Check that it's valid and has SSL and Certificates edit permission on the hrva.cc zone.", null));

        mockMvc.perform(as(ADMIN_TOKEN, post(DOMAINS + "/cloudflare-check")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status":"INVALID","checkedAt":"2026-10-29T09:00:00",
                        "message":"Cloudflare refused the token. Check that it's valid and has SSL and Certificates edit permission on the hrva.cc zone."}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void anAdminSetsReadsAndRemovesAnAllowance() throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, get(ALLOWANCES + ANA)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"userId\":%d,\"maxDomains\":1,\"reason\":null,\"grantedBy\":null,\"grantedAt\":null}".formatted(ANA),
                        JsonCompareMode.STRICT));

        final var granted = "{\"userId\":%d,\"maxDomains\":3,\"reason\":\"Agency with three brands\",\"grantedBy\":\"admin@hrva.cc\",\"grantedAt\":\"2026-10-29T09:00:00\"}"
                .formatted(ANA);
        mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + ANA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxDomains\":3,\"reason\":\" Agency with three brands \"}"))
                .andExpect(status().isOk())
                .andExpect(content().json(granted, JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, get(ALLOWANCES + ANA))).andExpect(content().json(granted, JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + ANA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxDomains\":5,\"reason\":\"Two more brands\"}"))
                .andExpect(jsonPath("$.maxDomains").value(5));
        assertThat(count("custom_domain_allowance")).isOne();

        mockMvc.perform(as(ADMIN_TOKEN, delete(ALLOWANCES + ANA))).andExpect(status().isNoContent());
        mockMvc.perform(as(ADMIN_TOKEN, delete(ALLOWANCES + ANA))).andExpect(status().isNoContent());
        mockMvc.perform(as(ADMIN_TOKEN, get(ALLOWANCES + ANA))).andExpect(jsonPath("$.maxDomains").value(1));

        assertThat(auditRows()).extracting(audit -> audit.get("action"))
                .containsExactly("SET_DOMAIN_ALLOWANCE", "SET_DOMAIN_ALLOWANCE", "REMOVE_DOMAIN_ALLOWANCE", "REMOVE_DOMAIN_ALLOWANCE");
        assertThat(auditRows().getFirst()).containsEntry("target_identifier", String.valueOf(ANA))
                .containsEntry("details", "maxDomains: 3, reason: Agency with three brands");
    }

    @Test
    void anAllowanceIsOneToTwentyWithAReason() throws Exception {
        for (final var body : List.of("{\"maxDomains\":0,\"reason\":\"Agency\"}", "{\"maxDomains\":21,\"reason\":\"Agency\"}", "{\"reason\":\"Agency\"}")) {
            mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + ANA)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"maxDomains\":\"Choose 1 to 20\"}", JsonCompareMode.STRICT));
        }
        mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + ANA)).contentType(MediaType.APPLICATION_JSON).content("{\"maxDomains\":3,\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"reason\":\"Reason is required\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + ANA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxDomains\":3,\"reason\":\"" + "a".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"reason\":\"Keep it to 300 characters or fewer\"}", JsonCompareMode.STRICT));

        assertThat(count("custom_domain_allowance")).isZero();
    }

    @Test
    void anUnknownAccountHasNoAllowance() throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, get(ALLOWANCES + 987654)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"User not found\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ADMIN_TOKEN, put(ALLOWANCES + 987654)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxDomains\":3,\"reason\":\"Agency\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(ADMIN_TOKEN, delete(ALLOWANCES + 987654))).andExpect(status().isNotFound());

        assertThat(count("custom_domain_allowance")).isZero();
    }

    private Listed statementsFor(final Statistics statistics) throws Exception {
        statistics.clear();
        final var body = mockMvc.perform(as(ADMIN_TOKEN, get(DOMAINS).param("size", "50")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final var statements = statistics.getPrepareStatementCount();
        return new Listed(JsonPath.<Integer>read(body, "$.content.length()"), statements);
    }

    private long activeDomainWithLinks() {
        final var id = domain(ANA, "go.example.com", "ACTIVE", NOW.minusDays(2));
        jdbcTemplate.update("""
                update custom_domain set review_note = 'Looks fine', reviewed_by = ?, reviewed_at = ?, activated_at = ?, last_checked_at = ?,
                cloudflare_id = 'b0361ed9-0000-4000-8000-000000000001', cloudflare_status = 'active', cloudflare_ssl_status = 'active',
                apply_flags = 'Registered 3 days ago' where id = ?""",
                ADMIN_EMAIL, NOW.minusDays(1), NOW.minusDays(1).plusMinutes(30), NOW.minusHours(1).plusNanos(500_000), id);
        link(ANA, id, "menu", 1000, null);
        link(ANA, id, "drinks", 204, null);
        final var blocked = link(ANA, null, "phish", 0, "BLOCKLIST");
        review(blocked);
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, resolved_at) values (?, 'old', ?, ?)", blocked, NOW.minusDays(9),
                NOW.minusDays(8));
        return id;
    }

    private void reconciled() {
        jdbcTemplate.update("""
                insert into custom_domain_reconcile (ran_at, cloudflare_total, ours_in_cloudflare, unknown_hostnames, missing_domain_ids, error)
                values (?, 3, 2, 'stray.example.com', ?, null)""", NOW.withHour(3).withMinute(40), "9:x.example.org");
    }

    private long domain(final Long ownerId, final String hostname, final String status, final LocalDateTime createdAt) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?)""", ownerId, hostname, hostname, status, createdAt, createdAt);
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private long link(final long ownerId, final Long domainId, final String alias, final long visits, final String blockReason) {
        jdbcTemplate.update("""
                insert into url (short_url, long_url, owner_id, domain_id, visits, active, block_reason, create_date)
                values (?, 'https://example.com/page', ?, ?, ?, ?, ?, ?)""", alias, ownerId, domainId, visits, blockReason == null, blockReason, NOW);
        return jdbcTemplate.queryForObject("select id from url where short_url = ?", Long.class, alias);
    }

    private void review(final long urlId) {
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'blocklist', ?, true)", urlId, NOW.minusDays(1));
    }

    private String statusOf(final long id) {
        return jdbcTemplate.queryForObject("select status from custom_domain where id = ?", String.class, id);
    }

    private int count(final String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private List<Map<String, Object>> auditRows() {
        return jdbcTemplate.queryForList("select action, performed_by, target_type, target_identifier, details, failed from audit_log order by id");
    }

    private void account(final long id, final String email, final LocalDateTime createdAt, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, ?, 'secret-hash', true, ?)", id, email,
                createdAt);
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
    }

    private void signedIn(final String token, final String email, final String role) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
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

    private record Listed(int rows, long statements) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, CustomDomainRepository.class, CustomDomainAllowanceRepository.class, AuditLogRepository.class}))
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
            AdminAuditAspect.class,
            DefaultAuditLogService.class,
            AdminCustomDomainController.class,
            DefaultCustomDomainAdminService.class,
            AdminDomainResponses.class,
            CustomDomainResponses.class,
            CustomDomainCapLock.class,
            CustomDomainReconcileRepository.class,
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
