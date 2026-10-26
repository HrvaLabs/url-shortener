package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.blocklist.BlocklistMemoryGuard;
import cc.hrva.urlshortener.service.blocklist.BlocklistParser;
import cc.hrva.urlshortener.service.blocklist.BlocklistRefresher;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.blocklist.LinkListParser;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultBlocklistSourceService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = RefreshBlocklistSourceFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:refresh-blocklist-source-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class RefreshBlocklistSourceFlowTest {

    private static final long ADMIN = 501L;
    private static final long SOURCE = 900L;
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final String LIST_URL = "https://lists.example/flow.txt";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @TempDir
    private Path downloads;

    private Runnable meanwhile = () -> {};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private BlocklistStore blocklistStore;

    @MockitoBean
    private PublicHttpFetcher publicHttpFetcher;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbcTemplate.update("insert into user_account (id, email, password) values (?, ?, 'secret-hash')", ADMIN, ADMIN_EMAIL);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", ADMIN, ROLE_USER);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", ADMIN, ROLE_ADMIN);
        jdbcTemplate.update("""
                insert into blocklist_source (id, name, url, kind, action, enabled, refresh_interval_hours)
                values (?, 'Flow list', ?, 'DOMAINS', 'BLOCK', true, 6)""", SOURCE, LIST_URL);
        when(tokenProvider.validateToken("admin-token")).thenReturn(true);
        when(tokenProvider.getAuthentication("admin-token"))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(ADMIN_EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(0, 1_000_000_000L));
        when(publicHttpFetcher.fetch(eq(LIST_URL), anyLong(), any(), any(), any())).thenAnswer(invocation -> {
            final var body = Files.writeString(Files.createTempFile(downloads, "flow-", ".txt"), "example.com\nexample.org\n");
            meanwhile.run();
            return new FetchResult(false, body, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from blocklist_source where id = ?", SOURCE);
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", ADMIN);
        jdbcTemplate.update("delete from user_account where id = ?", ADMIN);
    }

    @Test
    void aRefreshRecordsTheDownloadWhenNothingChangedMeanwhile() throws Exception {
        mockMvc.perform(refresh()).andExpect(status().isOk());

        verify(blocklistStore).replace(SOURCE, Set.of("example.com", "example.org"));
        assertThat(jdbcTemplate.queryForMap("select enabled, domain_count, last_error from blocklist_source where id = ?", SOURCE))
                .containsEntry("ENABLED", true)
                .containsEntry("DOMAIN_COUNT", 2L)
                .containsEntry("LAST_ERROR", null);
        assertThat(jdbcTemplate.queryForObject("select last_fetched_at from blocklist_source where id = ?", Object.class, SOURCE)).isNotNull();
    }

    @Test
    void aSourceTurnedOffDuringTheDownloadStaysOffAndKeepsItsSet() throws Exception {
        meanwhile = () -> jdbcTemplate.update("update blocklist_source set enabled = false, name = 'Renamed meanwhile' where id = ?", SOURCE);

        mockMvc.perform(refresh()).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap("select enabled, name, last_fetched_at, domain_count from blocklist_source where id = ?", SOURCE))
                .containsEntry("ENABLED", false)
                .containsEntry("NAME", "Renamed meanwhile")
                .containsEntry("LAST_FETCHED_AT", null)
                .containsEntry("DOMAIN_COUNT", null);
        verify(blocklistStore, never()).replace(eq(SOURCE), any());
    }

    @Test
    void aSourceThatChangedKindDuringTheDownloadKeepsTheNewKindAndItsSet() throws Exception {
        meanwhile = () -> jdbcTemplate.update("update blocklist_source set kind = 'LINKS', name = 'Renamed meanwhile' where id = ?", SOURCE);

        mockMvc.perform(refresh()).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap("select kind, name, last_fetched_at, domain_count from blocklist_source where id = ?", SOURCE))
                .containsEntry("KIND", "LINKS")
                .containsEntry("NAME", "Renamed meanwhile")
                .containsEntry("LAST_FETCHED_AT", null)
                .containsEntry("DOMAIN_COUNT", null);
        verify(blocklistStore, never()).replace(eq(SOURCE), any());
    }

    @Test
    void aSourceDeletedDuringTheDownloadIsNotStored() throws Exception {
        meanwhile = () -> jdbcTemplate.update("delete from blocklist_source where id = ?", SOURCE);

        mockMvc.perform(refresh()).andExpect(status().isOk());

        verify(blocklistStore, never()).replace(eq(SOURCE), any());
        assertThat(jdbcTemplate.queryForObject("select count(*) from blocklist_source where id = ?", Long.class, SOURCE)).isZero();
    }

    private static MockHttpServletRequestBuilder refresh() {
        return post("/api/v1/admin/blocklist-sources/{id}/refresh", SOURCE).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token");
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
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {BlocklistSourceRepository.class, UserRepository.class, AuditLogRepository.class}))
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
            AdminBlocklistController.class,
            DefaultBlocklistSourceService.class,
            BlocklistRefresher.class,
            BlocklistMemoryGuard.class,
            BlocklistParser.class,
            LinkListParser.class,
            AdminAuditAspect.class,
            DefaultAuditLogService.class
    })
    static class TestApplication {
    }

}
