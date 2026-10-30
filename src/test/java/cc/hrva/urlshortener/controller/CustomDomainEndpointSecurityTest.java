package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlDomainMoves;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainApplied;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.domain.CustomDomainSetup;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = CustomDomainEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-endpoints;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc"
        })
@AutoConfigureMockMvc
class CustomDomainEndpointSecurityTest {

    private static final String DOMAINS = "/api/v1/users/me/domains";
    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String APPLICATION = """
            {"hostname":"Go.Example.com.","reason":"Menu links for my cafe"}""";
    private static final String NOT_FOUND = "{\"error\":\"Custom domain not found\"}";
    private static final String OVER_THE_LIMIT = "{\"error\":\"You can have 1 custom domain. Remove one to apply for another.\"}";
    private static final String TAKEN = "{\"error\":\"This domain has already been applied for.\"}";
    private static final long ANA = 700L;
    private static final long BOB = 701L;
    private static final String ANA_EMAIL = "ana@hrva.cc";
    private static final String BOB_EMAIL = "bob@hrva.cc";
    private static final String ANA_TOKEN = "ana-token";
    private static final String BOB_TOKEN = "bob-token";
    private static final long ROLE_USER = 1L;
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private TestApplication.CommittedEvents committedEvents;

    @MockitoSpyBean
    private CustomDomainRepository customDomainRepository;

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

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private CustomDomainLifecycle customDomainLifecycle;

    @MockitoBean
    private CustomDomainProvisioner customDomainProvisioner;

    @MockitoBean
    private CustomDomainSetup customDomainSetup;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        account(ANA, ANA_EMAIL);
        account(BOB, BOB_EMAIL);
        signedIn(ANA_TOKEN, ANA_EMAIL);
        signedIn(BOB_TOKEN, BOB_EMAIL);
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        when(linkCheckService.check(anyString())).thenReturn(LinkVerdict.clean());
        committedEvents.clear();
    }

    @AfterEach
    void tearDown() {
        appProperties.getDomains().setEnabled(true);
        jdbcTemplate.update("delete from url where domain_id in (select id from custom_domain where owner_id in (?, ?))", ANA, BOB);
        jdbcTemplate.update("delete from url where owner_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from custom_domain_allowance where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from custom_domain where owner_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, BOB);
    }

    @Test
    void everyEndpointNeedsSignIn() throws Exception {
        final var id = domain(ANA, "pending.example.com", "PENDING_REVIEW");

        mockMvc.perform(get(DOMAINS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(DOMAINS).contentType(MediaType.APPLICATION_JSON).content(APPLICATION)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(DOMAINS + "/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(DOMAINS + "/" + id)).andExpect(status().isUnauthorized());

        assertThat(statusOf(id)).isEqualTo("PENDING_REVIEW");
        assertThat(countOf(ANA)).isEqualTo(1);
    }

    @Test
    void aSignedInUserAppliesListsReadsAndWithdraws() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hostname").value("go.example.com"))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        final var id = idOf("go.example.com");

        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domains.length()").value(1))
                .andExpect(jsonPath("$.domains[0].id").value(id))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.canApply").value(false));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"moved\":0,\"renamed\":[],\"deactivated\":0}", JsonCompareMode.STRICT));

        final var row = jdbcTemplate.queryForMap("select status, hostname_key, deleted_at, cloudflare_delete_pending from custom_domain where id = ?", id);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("hostname_key")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("cloudflare_delete_pending")).isEqualTo(false);
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domains[0].status").value("DELETED"))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.canApply").value(true));
    }

    @Test
    void anApplicationReadsBackInTheContractsShape() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isCreated());
        final var id = idOf("go.example.com");

        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":%d,"hostname":"go.example.com","status":"PENDING_REVIEW","reason":"Menu links for my cafe","reviewNote":null,
                         "createdAt":"2026-10-29T09:00:00","reviewedAt":null,"activatedAt":null,"lastCheckedAt":null,
                         "dnsRecord":null,"steps":null,"problem":null,"problemText":null,"disabledNote":null,"links":0}""".formatted(id),
                        JsonCompareMode.STRICT));
    }

    @Test
    void anApprovedDomainShowsItsRecordStepsAndProblem() throws Exception {
        final var id = domain(ANA, "menu.example.org", "AWAITING_DNS");
        jdbcTemplate.update("""
                update custom_domain set reviewed_at = ?, reviewed_by = 'admin@localhost', review_note = 'Welcome', cloudflare_id = 'cf-1',
                    cloudflare_status = 'pending', cloudflare_ssl_status = 'pending_validation', problem = 'DNS_NOT_FOUND', last_checked_at = ?
                where id = ?""", NOW.minusMinutes(20), NOW.minusMinutes(1), id);
        jdbcTemplate.update("insert into url (short_url, long_url, domain_id, active) values ('menu', 'https://example.org/menu', ?, true)", id);
        jdbcTemplate.update("insert into url (short_url, long_url, domain_id, active) values ('old', 'https://example.org/old', ?, false)", id);

        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":%d,"hostname":"menu.example.org","status":"AWAITING_DNS","reason":"Menu links for my cafe","reviewNote":"Welcome",
                         "createdAt":"2026-10-28T09:00:00","reviewedAt":"2026-10-29T08:40:00","activatedAt":null,"lastCheckedAt":"2026-10-29T08:59:00",
                         "dnsRecord":{"type":"CNAME","name":"menu.example.org","target":"domains.hrva.cc"},
                         "steps":{"dnsFound":false,"certificateIssued":false},
                         "problem":"DNS_NOT_FOUND","problemText":"We can't see the CNAME record yet. DNS changes can take up to an hour.",
                         "disabledNote":null,"links":2}""".formatted(id), JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domains[0].links").value(2));
        verify(rateLimiter).tryAcquire("domain-check:domain:" + id, 1, Duration.ofSeconds(15));
        verify(customDomainLifecycle).checkNow(id);
        verifyNoInteractions(customDomainProvisioner);
    }

    @Test
    void anEmptyApplicationNamesBothFields() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"hostname":"Enter a domain, like go.example.com","reason":"Tell us what you'll use it for"}""", JsonCompareMode.STRICT));

        assertThat(countOf(ANA)).isZero();
    }

    @Test
    void anApexIsRefusedWithItsText() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"example.com\",\"reason\":\"Menu links for my cafe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"hostname":"Use a subdomain, like go.example.com. Root domains like example.com aren't supported yet."}""",
                        JsonCompareMode.STRICT));

        assertThat(countOf(ANA)).isZero();
    }

    @Test
    void theReasonIsTrimmedBeforeItsLengthIsChecked() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"go.example.com\",\"reason\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"reason\":\"Keep it to 300 characters or fewer\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"go.example.com\",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"reason\":\"Tell us what you'll use it for\"}", JsonCompareMode.STRICT));

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"go.example.com\",\"reason\":\"  " + "x".repeat(300) + " \\n\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("x".repeat(300)));
    }

    @Test
    void anotherUsersDomainIsNotFound() throws Exception {
        final var id = domain(BOB, "bob.example.com", "PENDING_REVIEW");

        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/999999")))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"domains\":[],\"limit\":1,\"used\":0,\"canApply\":true}", JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void aHostnameSomeoneElseHoldsIsAConflict() throws Exception {
        domain(BOB, "go.example.com", "ACTIVE");

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"This domain has already been applied for.\"}", JsonCompareMode.STRICT));

        assertThat(countOf(ANA)).isZero();
    }

    @Test
    void rejectedAndDeletedApplicationsDoNotCount() throws Exception {
        final var rejected = domain(ANA, "old.example.com", "REJECTED");
        final var deleted = domain(ANA, "gone.example.com", "DELETED");
        jdbcTemplate.update("update custom_domain set hostname_key = null where id in (?, ?)", rejected, deleted);

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isCreated());
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"old.example.com\",\"reason\":\"Again\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"You can have 1 custom domain. Remove one to apply for another.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anAllowanceRaisesTheLimit() throws Exception {
        jdbcTemplate.update("insert into custom_domain_allowance (user_id, max_domains, reason, granted_by, granted_at) values (?, 2, 'Agency', 'admin@localhost', ?)",
                ANA, NOW);

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isCreated());
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"two.example.com\",\"reason\":\"Second brand\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"three.example.com\",\"reason\":\"Third brand\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"You can have 2 custom domains. Remove one to apply for another.\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS)))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.used").value(2))
                .andExpect(jsonPath("$.domains[0].hostname").value("two.example.com"));
    }

    @Test
    void turnedOffIs503() throws Exception {
        appProperties.getDomains().setEnabled(false);

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"Custom domains aren't available right now.\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canApply").value(false));

        assertThat(countOf(ANA)).isZero();
    }

    @Test
    void applicationsOverTheRateLimitAre429() throws Exception {
        when(rateLimiter.tryAcquire(eq("domain-apply:user:" + ANA), eq(5), eq(Duration.ofHours(24)))).thenReturn(false);

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"You've applied for a lot of domains today. Try again tomorrow.\"}", JsonCompareMode.STRICT));

        assertThat(countOf(ANA)).isZero();
    }

    @Test
    void aLinksModeOtherThanMoveOrDeactivateIsRefused() throws Exception {
        final var id = domain(ANA, "pending.example.com", "PENDING_REVIEW");

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id + "?links=FOO")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"links must be MOVE or DEACTIVATE\"}", JsonCompareMode.STRICT));
        assertThat(statusOf(id)).isEqualTo("PENDING_REVIEW");

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id + "?links=MOVE")))
                .andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("DELETED");
    }

    @Test
    void removingAnActiveDomainMovesItsLinksToHrvaAndAnswersTheSummary() throws Exception {
        final var id = domain(ANA, "go.example.com", "ACTIVE");
        jdbcTemplate.update("update custom_domain set cloudflare_id = 'cf-1' where id = ?", id);
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values ('menu', 'https://example.org/menu', ?, ?, true)",
                ANA, id);
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values ('cafe', 'https://example.org/cafe', ?, ?, false)",
                ANA, id);
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, active) values ('menu', 'https://example.net/menu', ?, true)", BOB);
        when(urlService.nextFreeShortUrl(null)).thenReturn("k3J9x");

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id).param("links", "MOVE")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"moved\":2,\"renamed\":[{\"from\":\"menu\",\"to\":\"k3J9x\"}],\"deactivated\":0}",
                        JsonCompareMode.STRICT));

        assertThat(jdbcTemplate.queryForList("select short_url from url where owner_id = ? and domain_id is null order by short_url", String.class, ANA))
                .containsExactly("cafe", "k3J9x");
        assertThat(jdbcTemplate.queryForMap("select status, cloudflare_delete_pending from custom_domain where id = ?", id))
                .containsEntry("STATUS", "DELETED").containsEntry("CLOUDFLARE_DELETE_PENDING", true);
    }

    @Test
    void removingAnActiveDomainTurnsItsLinksOffByDefault() throws Exception {
        final var id = domain(ANA, "go.example.com", "ACTIVE");
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values ('menu', 'https://example.org/menu', ?, ?, true)",
                ANA, id);

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"moved\":0,\"renamed\":[],\"deactivated\":1}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));

        assertThat(jdbcTemplate.queryForList("select active from url where domain_id = ?", Boolean.class, id)).containsExactly(false);
        assertThat(committedEvents.events()).containsExactly(new CustomDomainChangedEvent(id));
    }

    @Test
    void someoneElsesDomainCanNeitherBeRemovedNorRetried() throws Exception {
        final var id = domain(BOB, "bob.example.org", "ERROR");

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id).param("links", "MOVE")))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS + "/" + id + "/retry")))
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND, JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("ERROR");
        verifyNoInteractions(customDomainSetup);
    }

    @Test
    void theOwnerRetriesAFailedDomain() throws Exception {
        final var id = domain(ANA, "go.example.com", "ERROR");
        jdbcTemplate.update("update custom_domain set problem = 'PROVISIONING_FAILED' where id = ?", id);

        mockMvc.perform(post(DOMAINS + "/" + id + "/retry")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS + "/" + id + "/retry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.problem").value("PROVISIONING_FAILED"))
                .andExpect(jsonPath("$.owner").doesNotExist());

        verify(customDomainSetup).retry(argThat(domain -> domain.getId() == id));
        verify(rateLimiter).tryAcquire("domain-retry:domain:" + id, 1, Duration.ofSeconds(15));
    }

    @Test
    void pollingNeverHoldsARetryBackButASecondRetryWithinFifteenSecondsIsTooSoon() throws Exception {
        final var id = domain(ANA, "go.example.com", "ERROR");
        jdbcTemplate.update("update custom_domain set problem = 'CNAME_REMOVED', cloudflare_id = 'cf-1' where id = ?", id);
        when(rateLimiter.tryAcquire("domain-check:domain:" + id, 1, Duration.ofSeconds(15))).thenReturn(true, false);
        when(rateLimiter.tryAcquire("domain-retry:domain:" + id, 1, Duration.ofSeconds(15))).thenReturn(true, false);

        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id))).andExpect(status().isOk());
        mockMvc.perform(as(ANA_TOKEN, get(DOMAINS + "/" + id))).andExpect(status().isOk());
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS + "/" + id + "/retry"))).andExpect(status().isOk());
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS + "/" + id + "/retry")))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"Wait a few seconds and try again.\"}", JsonCompareMode.STRICT));

        verify(customDomainLifecycle, times(1)).checkNow(id);
        verify(customDomainSetup, times(1)).retry(argThat(domain -> domain.getId() == id));
    }

    @Test
    void anOwnerCannotMoveTheLinksOfADomainHrvaTurnedOffButCanTurnThemOff() throws Exception {
        final var id = domain(ANA, "go.example.com", "DISABLED");
        jdbcTemplate.update("update custom_domain set disabled_reason = 'ADMIN', disabled_note = 'Phishing', cloudflare_id = 'cf-1' where id = ?", id);
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values ('menu', 'https://example.org/menu', ?, ?, true)",
                ANA, id);

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id).param("links", "MOVE")))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"error":"This domain was turned off by hrva.cc, so its links can't move to hrva.cc. Remove it with its links turned off instead."}""",
                        JsonCompareMode.STRICT));
        assertThat(statusOf(id)).isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("select domain_id from url where short_url = 'menu' and owner_id = ?", Long.class, ANA)).isEqualTo(id);

        mockMvc.perform(as(ANA_TOKEN, delete(DOMAINS + "/" + id).param("links", "DEACTIVATE")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"moved\":0,\"renamed\":[],\"deactivated\":1}", JsonCompareMode.STRICT));
    }

    @Test
    void theOwnerHasNothingToRetryOnAWaitingDomain() throws Exception {
        final var id = domain(ANA, "go.example.com", "AWAITING_DNS");

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS + "/" + id + "/retry")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"There's nothing to retry for this domain.\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(customDomainSetup);
    }

    @Test
    void theAlertAndTheChangeGoOutAfterTheApplicationCommits() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isCreated());
        final var id = idOf("go.example.com");

        assertThat(committedEvents.events()).containsExactly(new DomainApplied(id, "go.example.com", ANA, List.of()), new CustomDomainChangedEvent(id));
    }

    @Test
    void aRefusedApplicationSendsNothing() throws Exception {
        domain(BOB, "go.example.com", "PENDING_REVIEW");

        mockMvc.perform(as(ANA_TOKEN, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isConflict());

        assertThat(committedEvents.events()).isEmpty();
    }

    @Test
    void twoAppliesAtOnceLetOnlyOneThrough() throws Exception {
        bothPassTheLinkCheckTogether();
        final var arrived = bothReachTheHostnameCheck(Duration.ofSeconds(1));

        final var first = CompletableFuture.supplyAsync(() -> appliedAnswer(ANA_TOKEN, "one.example.com"));
        final var second = CompletableFuture.supplyAsync(() -> appliedAnswer(ANA_TOKEN, "two.example.com"));

        final var answers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 400);
        assertThat(answers).filteredOn(answer -> answer.getStatus() == 400).singleElement()
                .satisfies(refused -> assertThat(refused.getContentAsString()).isEqualTo(OVER_THE_LIMIT));
        assertThat(countOf(ANA)).isOne();
        assertThat(committedEvents.events()).filteredOn(DomainApplied.class::isInstance).hasSize(1);
        assertThat(arrived.getCount()).isOne();
    }

    @Test
    void twoUsersApplyingForOneHostnameAtOnce() throws Exception {
        bothPassTheLinkCheckTogether();
        final var arrived = bothReachTheHostnameCheck(Duration.ofSeconds(10));

        final var first = CompletableFuture.supplyAsync(() -> appliedAnswer(ANA_TOKEN, "go.example.com"));
        final var second = CompletableFuture.supplyAsync(() -> appliedAnswer(BOB_TOKEN, "go.example.com"));

        final var answers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
        assertThat(answers).filteredOn(answer -> answer.getStatus() == 409).singleElement()
                .satisfies(refused -> assertThat(refused.getContentAsString()).isEqualTo(TAKEN));
        assertThat(jdbcTemplate.queryForObject("select count(*) from custom_domain where hostname = 'go.example.com'", Integer.class)).isOne();
        assertThat(committedEvents.events()).filteredOn(DomainApplied.class::isInstance).hasSize(1);
        verify(customDomainRepository, times(2)).findByHostnameKey("go.example.com");
        assertThat(arrived.getCount()).isZero();
    }

    private void bothPassTheLinkCheckTogether() {
        final var bothChecking = new CyclicBarrier(2);
        when(linkCheckService.check(anyString())).thenAnswer(invocation -> {
            bothChecking.await(10, TimeUnit.SECONDS);
            return LinkVerdict.clean();
        });
    }

    private CountDownLatch bothReachTheHostnameCheck(final Duration longestWait) {
        final var arrived = new CountDownLatch(2);
        doAnswer(invocation -> {
            final var found = mockingDetails(customDomainRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (arrived.getCount() > 0) {
                arrived.countDown();
                arrived.await(longestWait.toMillis(), TimeUnit.MILLISECONDS);
            }
            return found;
        }).when(customDomainRepository).findByHostnameKey(anyString());
        return arrived;
    }

    private MockHttpServletResponse appliedAnswer(final String token, final String hostname) {
        try {
            return mockMvc.perform(as(token, post(DOMAINS)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hostname\":\"" + hostname + "\",\"reason\":\"Menu links for my cafe\"}")).andReturn().getResponse();
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long domain(final long ownerId, final String hostname, final String status) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?)""", ownerId, hostname, hostname, status, NOW.minusDays(1), NOW.minusDays(1));
        return idOf(hostname, ownerId);
    }

    private long idOf(final String hostname) {
        return idOf(hostname, ANA);
    }

    private long idOf(final String hostname, final long ownerId) {
        return jdbcTemplate.queryForObject("select max(id) from custom_domain where hostname = ? and owner_id = ?", Long.class, hostname, ownerId);
    }

    private String statusOf(final long id) {
        return jdbcTemplate.queryForObject("select status from custom_domain where id = ?", String.class, id);
    }

    private int countOf(final long ownerId) {
        return jdbcTemplate.queryForObject("select count(*) from custom_domain where owner_id = ?", Integer.class, ownerId);
    }

    private static MockHttpServletRequestBuilder as(final String token, final MockHttpServletRequestBuilder request) {
        return request.header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token);
    }

    private void account(final long id, final String email) {
        jdbcTemplate.update("insert into user_account (id, email, password, active) values (?, ?, 'secret-hash', true)", id, email);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, ROLE_USER);
    }

    private void signedIn(final String token, final String email) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private User signedInAccount() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> userRepository.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName()).orElse(null));
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
                    classes = {UserRepository.class, CustomDomainRepository.class, CustomDomainAllowanceRepository.class, UrlRepository.class}))
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
            AccountLock.class,
            CustomDomainController.class,
            DefaultCustomDomainService.class,
            CustomDomainHostnames.class,
            CustomDomainLimits.class,
            CustomDomainResponses.class,
            CustomDomainRemoval.class,
            UrlDomainMoves.class,
            UrlCache.class,
            TestApplication.FixedClock.class,
            TestApplication.CommittedEvents.class
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

        static class CommittedEvents {

            private final List<Object> events = new CopyOnWriteArrayList<>();

            @TransactionalEventListener
            public void onAlert(final AdminAlertEvent alert) {
                events.add(alert);
            }

            @TransactionalEventListener
            public void onChange(final CustomDomainChangedEvent change) {
                events.add(change);
            }

            List<Object> events() {
                return List.copyOf(events);
            }

            void clear() {
                events.clear();
            }

        }

    }

}
