package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UrlDomainMoves;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.SessionToken;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.AdminDomainRequestResponses;
import cc.hrva.urlshortener.service.domain.AdminDomainResponses;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck;
import cc.hrva.urlshortener.service.domain.CustomDomainApproval;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.service.domain.CustomDomainModeration;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.domain.CustomDomainSetup;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainAdminService;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainRequestAdminService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.domain.ShortLinks;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminDomainRequestSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:admin-domain-requests;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
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
class AdminDomainRequestSecurityTest {

    private static final String REQUESTS = "/api/v1/admin/domains/requests";
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
    private static final String ALREADY_REVIEWED = "{\"detail\":\"This request was already reviewed.\"}";
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
    private CustomDomainLifecycle customDomainLifecycle;

    @MockitoBean
    private CustomDomainReconciler customDomainReconciler;

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
    private OAuth2LoginUserService oAuth2LoginUserService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private TaskScheduler taskScheduler;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        account(ADMIN, ADMIN_EMAIL, NOW.minusYears(1), ROLE_USER, ROLE_ADMIN);
        jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", ADMIN);
        account(ANA, ANA_EMAIL, NOW.minusDays(40), ROLE_USER);
        account(BOB, BOB_EMAIL, NOW.minusDays(3), ROLE_USER);
        signedIn(ADMIN_TOKEN, ADMIN_EMAIL, "ROLE_ADMIN");
        signedIn(USER_TOKEN, ANA_EMAIL, "ROLE_USER");
        when(userService.getUserFromToken()).thenReturn(User.builder().id(ADMIN).email(ADMIN_EMAIL).build());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from link_review");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from custom_domain_request");
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from custom_domain_allowance");
        jdbcTemplate.update("delete from user_account_authorities");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void everyEndpointIsForAdminsOnly() throws Exception {
        final var id = request(ANA, 3, "WAITING", NOW.minusDays(1));
        final var requests = List.of(
                get(REQUESTS),
                post(REQUESTS + "/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"note\":null}"),
                post(REQUESTS + "/" + id + "/decline").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"No\"}"));

        for (final var request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
            mockMvc.perform(as(USER_TOKEN, request)).andExpect(status().isForbidden());
        }

        assertThat(statusOf(id)).isEqualTo("WAITING");
        assertThat(count("custom_domain_allowance")).isZero();
        assertThat(count("audit_log")).isZero();
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void theListShowsEachRequestWithTheAccountsLimitDomainsAndStanding() throws Exception {
        domain(ANA, "go.example.com", "ACTIVE");
        domain(ANA, "old.example.com", "REJECTED");
        final var blocked = link(ANA, "phish", "BLOCKLIST");
        review(blocked);
        link(ANA, "menu", null);
        final var id = request(ANA, 3, "WAITING", NOW.minusDays(2));
        when(scammerSignalService.possibleScammers(anyCollection())).thenReturn(Set.of(ANA));

        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"id":%d,"status":"WAITING","requestedTotal":3,"limitAtRequest":1,"currentLimit":1,"used":1,
                        "reason":"One domain for each of my three shops","adminNote":null,"reviewedBy":null,"reviewedAt":null,
                        "createdAt":"2026-10-27T09:00:00Z",
                        "owner":{"id":%d,"email":"ana@example.com","active":true,"createdAt":"2026-09-19T09:00:00Z","links":2,"blockedLinks":1,
                        "openReviews":1,"possibleScammer":true}}],
                        "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}
                        """.formatted(id, ANA), JsonCompareMode.STRICT));
    }

    @Test
    void theListFiltersByStatusSearchesAndSorts() throws Exception {
        final var older = request(ANA, 5, "APPROVED", NOW.minusDays(5));
        final var newer = request(BOB, 2, "WAITING", NOW.minusDays(1));
        final var middle = request(ANA, 3, "DECLINED", NOW.minusDays(3));

        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS)))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) newer, (int) middle, (int) older)));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("status", "WAITING")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) newer)))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("search", " ANA@example ")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) middle, (int) older)));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("search", "three SHOPS")))
                .andExpect(jsonPath("$.content.length()").value(3));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("search", "%")))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("sort", "requestedTotal,desc")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) older, (int) middle, (int) newer)));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("sort", "createdAt,asc")))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) older, (int) middle, (int) newer)));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("sort", "reason")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"detail\":\"Sort must be createdAt, requestedTotal or status\"}", JsonCompareMode.LENIENT));
        mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("status", "PENDING")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theListRunsTheSameStatementsForOneRowAndTwenty() throws Exception {
        domain(ANA, "go.example.com", "ACTIVE");
        request(ANA, 3, "WAITING", NOW.minusDays(1));
        allowance(ANA, 2);
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        final var forOne = statementsFor(statistics);
        IntStream.range(0, 19).forEach(index -> {
            final var owner = FIRST_EXTRA_OWNER + index;
            account(owner, "owner" + index + "@example.com", NOW.minusDays(10), ROLE_USER);
            domain(owner, "d" + index + ".example.com", "ACTIVE");
            review(link(owner, "b" + index, "BLOCKLIST"));
            allowance(owner, 2);
            request(owner, 4, "WAITING", NOW.minusHours(index + 1));
        });
        final var forTwenty = statementsFor(statistics);

        assertThat(forTwenty.rows()).isEqualTo(20);
        assertThat(forOne.rows()).isEqualTo(1);
        assertThat(forTwenty.statements()).isEqualTo(forOne.statements());
    }

    @Test
    void anApprovalRaisesTheLimitWithTheRequestsReasonAndTheAuditRowSaysSo() throws Exception {
        domain(ANA, "go.example.com", "ACTIVE");
        final var id = request(ANA, 3, "WAITING", NOW.minusDays(1));

        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  Fine for your shops  \"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":%d,"status":"APPROVED","requestedTotal":3,"limitAtRequest":1,"currentLimit":3,"used":1,"adminNote":"Fine for your shops",
                        "reviewedBy":"admin@hrva.cc","reviewedAt":"2026-10-29T09:00:00Z","owner":{"email":"ana@example.com"}}""".formatted(id),
                        JsonCompareMode.LENIENT));

        assertThat(jdbcTemplate.queryForMap("select max_domains, reason, granted_by, granted_at from custom_domain_allowance where user_id = ?", ANA))
                .containsEntry("MAX_DOMAINS", 3)
                .containsEntry("REASON", "One domain for each of my three shops")
                .containsEntry("GRANTED_BY", ADMIN_EMAIL);
        assertThat(auditRows()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("action", "APPROVE_DOMAIN_REQUEST")
                .containsEntry("performed_by", ADMIN_EMAIL)
                .containsEntry("target_type", "DomainRequest")
                .containsEntry("target_identifier", String.valueOf(id))
                .containsEntry("failed", false));
        verify(sendingEmailService).sendDomainRequestApprovedEmail(ANA_EMAIL, 3, "Fine for your shops");
    }

    @Test
    void aDeclineKeepsTheLimitAndTellsTheOwner() throws Exception {
        domain(ANA, "go.example.com", "ACTIVE");
        final var id = request(ANA, 3, "WAITING", NOW.minusDays(1));

        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + id + "/decline")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.currentLimit").value(1))
                .andExpect(jsonPath("$.adminNote").doesNotExist());

        assertThat(count("custom_domain_allowance")).isZero();
        assertThat(auditRows()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("action", "DECLINE_DOMAIN_REQUEST")
                .containsEntry("target_type", "DomainRequest")
                .containsEntry("target_identifier", String.valueOf(id))
                .containsEntry("failed", false));
        verify(sendingEmailService).sendDomainRequestDeclinedEmail(ANA_EMAIL, 3, 1, null);
    }

    @Test
    void refusedReviewsAnswerWithTheirTextsAndChangeNothing() throws Exception {
        final var reviewed = request(ANA, 3, "DECLINED", NOW.minusDays(2));
        final var raisedMeanwhile = request(BOB, 3, "WAITING", NOW.minusDays(1));
        allowance(BOB, 4);

        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + reviewed + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json(ALREADY_REVIEWED, JsonCompareMode.LENIENT));
        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + reviewed + "/decline")))
                .andExpect(status().isConflict())
                .andExpect(content().json(ALREADY_REVIEWED, JsonCompareMode.LENIENT));
        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + raisedMeanwhile + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"detail\":\"This account's limit is already 4. Decline the request instead.\"}", JsonCompareMode.LENIENT));
        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/987654/approve")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"detail\":\"Request not found\"}", JsonCompareMode.LENIENT));
        jdbcTemplate.update("delete from custom_domain_allowance where user_id = ?", BOB);
        jdbcTemplate.update("update user_account set active = false where id = ?", BOB);
        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + raisedMeanwhile + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"detail\":\"This account is deactivated, so its request can't be approved.\"}", JsonCompareMode.LENIENT));
        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + raisedMeanwhile + "/decline")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"errors\":{\"note\":\"Keep it to 500 characters or fewer\"}}", JsonCompareMode.LENIENT));

        assertThat(statusOf(reviewed)).isEqualTo("DECLINED");
        assertThat(statusOf(raisedMeanwhile)).isEqualTo("WAITING");
        assertThat(count("custom_domain_allowance")).isZero();
        assertThat(auditRows()).extracting(audit -> audit.get("action"), audit -> audit.get("failed")).containsExactly(
                tuple("APPROVE_DOMAIN_REQUEST", true),
                tuple("DECLINE_DOMAIN_REQUEST", true),
                tuple("APPROVE_DOMAIN_REQUEST", true),
                tuple("APPROVE_DOMAIN_REQUEST", true),
                tuple("APPROVE_DOMAIN_REQUEST", true));
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void reviewEmailsGoToTheOwnerOnceTheReviewHasCommitted() throws Exception {
        domain(ANA, "go.example.com", "ACTIVE");
        domain(BOB, "links.example.org", "ACTIVE");
        final var approving = request(ANA, 3, "WAITING", NOW.minusDays(1));
        final var declining = request(BOB, 2, "WAITING", NOW.minusDays(1));
        final var seenWhenSent = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            seenWhenSent.add(TransactionSynchronizationManager.isActualTransactionActive() + " " + committedStatusOf(approving));
            return null;
        }).when(sendingEmailService).sendDomainRequestApprovedEmail(anyString(), anyInt(), any());
        doAnswer(invocation -> {
            seenWhenSent.add(TransactionSynchronizationManager.isActualTransactionActive() + " " + committedStatusOf(declining));
            return null;
        }).when(sendingEmailService).sendDomainRequestDeclinedEmail(anyString(), anyInt(), anyInt(), any());

        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + approving + "/approve"))).andExpect(status().isOk());
        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + declining + "/decline"))).andExpect(status().isOk());

        assertThat(seenWhenSent).containsExactly("false APPROVED", "false DECLINED");
    }

    @Test
    void aFailedEmailDoesNotUndoTheReview() throws Exception {
        domain(ANA, "go.example.com", "ACTIVE");
        final var id = request(ANA, 3, "WAITING", NOW.minusDays(1));
        doAnswer(invocation -> {
            throw new IllegalStateException("mail server down");
        }).when(sendingEmailService).sendDomainRequestApprovedEmail(anyString(), anyInt(), any());

        mockMvc.perform(as(ADMIN_TOKEN, post(REQUESTS + "/" + id + "/approve"))).andExpect(status().isOk());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("select max_domains from custom_domain_allowance where user_id = ?", Integer.class, ANA)).isEqualTo(3);
    }

    private String committedStatusOf(final long id) {
        return CompletableFuture.supplyAsync(() -> statusOf(id)).join();
    }

    private Listed statementsFor(final Statistics statistics) throws Exception {
        statistics.clear();
        final var body = mockMvc.perform(as(ADMIN_TOKEN, get(REQUESTS).param("size", "50")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final var statements = statistics.getPrepareStatementCount();
        return new Listed(JsonPath.<Integer>read(body, "$.content.length()"), statements);
    }

    private long request(final long userId, final int requestedTotal, final String status, final LocalDateTime createdAt) {
        final var reviewed = "WAITING".equals(status) ? null : createdAt.plusHours(1);
        jdbcTemplate.update("""
                insert into custom_domain_request (user_id, requested_total, limit_at_request, reason, status, reviewed_by, reviewed_at, created_at)
                values (?, ?, 1, 'One domain for each of my three shops', ?, ?, ?, ?)""",
                userId, requestedTotal, status, reviewed == null ? null : ADMIN_EMAIL, reviewed, createdAt);
        return jdbcTemplate.queryForObject("select max(id) from custom_domain_request where user_id = ?", Long.class, userId);
    }

    private void domain(final long ownerId, final String hostname, final String status) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?)""", ownerId, hostname, "REJECTED".equals(status) ? null : hostname, status,
                NOW.minusDays(5), NOW.minusDays(5));
    }

    private long link(final long ownerId, final String alias, final String blockReason) {
        jdbcTemplate.update("""
                insert into url (short_url, long_url, owner_id, visits, active, block_reason, create_date)
                values (?, 'https://example.com/page', ?, 0, ?, ?, ?)""", alias, ownerId, blockReason == null, blockReason, NOW);
        return jdbcTemplate.queryForObject("select id from url where short_url = ?", Long.class, alias);
    }

    private void review(final long urlId) {
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'blocklist', ?, true)", urlId, NOW.minusDays(1));
    }

    private void allowance(final long userId, final int maxDomains) {
        jdbcTemplate.update("insert into custom_domain_allowance (user_id, max_domains, reason, granted_by, granted_at) values (?, ?, 'Agency', ?, ?)",
                userId, maxDomains, ADMIN_EMAIL, NOW.minusDays(1));
    }

    private String statusOf(final long id) {
        return jdbcTemplate.queryForObject("select status from custom_domain_request where id = ?", String.class, id);
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

    private record Listed(int rows, long statements) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, CustomDomainRepository.class, CustomDomainAllowanceRepository.class, CustomDomainRequestRepository.class,
                            AuditLogRepository.class, UrlRepository.class}))
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
            AdminDomainRequestController.class,
            DefaultCustomDomainRequestAdminService.class,
            AdminDomainRequestResponses.class,
            DefaultCustomDomainAdminService.class,
            AdminDomainResponses.class,
            CustomDomainResponses.class,
            CustomDomainLimits.class,
            CustomDomainCapLock.class,
            CustomDomainReconcileRepository.class,
            CustomDomainSetup.class,
            CustomDomainApproval.class,
            CustomDomainModeration.class,
            CustomDomainRemoval.class,
            UrlDomainMoves.class,
            UrlCache.class,
            TestApplication.FixedClock.class
    })
    static class TestApplication {

        @TestConfiguration
        static class FixedClock {

            @Bean
            Clock clock() {
                return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
            }

            @Bean
            CacheManager cacheManager() {
                return new ConcurrentMapCacheManager("urls");
            }

        }

    }

}
