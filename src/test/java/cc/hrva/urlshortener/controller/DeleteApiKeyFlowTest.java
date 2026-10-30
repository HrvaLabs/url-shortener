package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = DeleteApiKeyFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:delete-api-key-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class DeleteApiKeyFlowTest {

    private static final long OWNER = 400L;
    private static final long OTHER = 401L;
    private static final long ADMIN = 402L;
    private static final long OWNERS_REVOKED_KEY = 410L;
    private static final long OWNERS_ACTIVE_KEY = 411L;
    private static final long OWNERS_EXPIRED_KEY = 412L;
    private static final long OTHERS_REVOKED_KEY = 413L;
    private static final long ADMINS_REVOKED_KEY = 414L;
    private static final long ADMINS_ACTIVE_KEY = 415L;
    private static final String OWNER_EMAIL = "owner@hrva.cc";
    private static final String OTHER_EMAIL = "other@hrva.cc";
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final String OWNER_TOKEN = "Bearer owner-token";
    private static final String OTHER_TOKEN = "Bearer other-token";
    private static final String ADMIN_TOKEN = "Bearer admin-token";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final RSAKey SIGNING_KEY = generateSigningKey();

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
    private OAuth2LoginUserService oAuth2LoginUserService;

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
        account(OWNER, OWNER_EMAIL, ROLE_USER);
        account(OTHER, OTHER_EMAIL, ROLE_USER);
        account(ADMIN, ADMIN_EMAIL, ROLE_USER, ROLE_ADMIN);
        key(OWNERS_REVOKED_KEY, OWNER, false, "2027-01-01");
        key(OWNERS_ACTIVE_KEY, OWNER, true, "2027-01-01");
        key(OWNERS_EXPIRED_KEY, OWNER, false, "2026-10-21");
        key(OTHERS_REVOKED_KEY, OTHER, false, "2027-01-01");
        key(ADMINS_REVOKED_KEY, ADMIN, false, "2027-01-01");
        key(ADMINS_ACTIVE_KEY, ADMIN, true, "2027-01-01");
        signedIn("owner-token", OWNER_EMAIL, "ROLE_USER");
        signedIn("other-token", OTHER_EMAIL, "ROLE_USER");
        signedIn("admin-token", ADMIN_EMAIL, "ROLE_ADMIN");
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from api_key where owner_id in (?, ?, ?)", OWNER, OTHER, ADMIN);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?, ?)", OWNER, OTHER, ADMIN);
        jdbcTemplate.update("delete from user_account where id in (?, ?, ?)", OWNER, OTHER, ADMIN);
    }

    @Test
    void theOwnerDeletesTheirRevokedKey() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_REVOKED_KEY).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(keyIds()).doesNotContain(OWNERS_REVOKED_KEY).contains(OWNERS_ACTIVE_KEY, OWNERS_EXPIRED_KEY);
    }

    @Test
    void theOwnerDeletesTheirKeyAfterListingIt() throws Exception {
        mockMvc.perform(get("/api/v1/api-keys").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_EXPIRED_KEY).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/api-keys").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void anActiveKeyMustBeRevokedFirst() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_ACTIVE_KEY).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Revoke this key before deleting it."));

        assertThat(keyIds()).contains(OWNERS_ACTIVE_KEY);
    }

    @Test
    void anotherUserIsRefusedLikeARevoke() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_REVOKED_KEY).header(JwtFilter.AUTHORIZATION_HEADER, OTHER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("You don't have authorization for this action"));
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_ACTIVE_KEY).header(JwtFilter.AUTHORIZATION_HEADER, OTHER_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(keyIds()).contains(OWNERS_REVOKED_KEY, OWNERS_ACTIVE_KEY);
    }

    @Test
    void anAdminDeletesSomeoneElsesInactiveKey() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_EXPIRED_KEY).header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isNoContent());

        assertThat(keyIds()).doesNotContain(OWNERS_EXPIRED_KEY);
    }

    @Test
    void anAdminMustRevokeSomeoneElsesActiveKeyFirst() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_ACTIVE_KEY).header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isConflict());

        assertThat(keyIds()).contains(OWNERS_ACTIVE_KEY);
    }

    @Test
    void anUnknownKeyIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", 999L).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Api key doesn't exist"));
    }

    @Test
    void theBulkDeleteRemovesOnlyTheCallersInactiveKeys() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/inactive").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));

        assertThat(keyIds()).containsExactlyInAnyOrder(OWNERS_ACTIVE_KEY, OTHERS_REVOKED_KEY, ADMINS_REVOKED_KEY, ADMINS_ACTIVE_KEY);
    }

    @Test
    void theBulkDeleteNeverTouchesOtherUsersKeysEvenForAnAdmin() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/inactive").header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        assertThat(keyIds()).containsExactlyInAnyOrder(
                OWNERS_REVOKED_KEY, OWNERS_ACTIVE_KEY, OWNERS_EXPIRED_KEY, OTHERS_REVOKED_KEY, ADMINS_ACTIVE_KEY);
    }

    @Test
    void theBulkDeleteWithNothingInactiveDeletesNothing() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/inactive").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(jsonPath("$.deleted").value(2));

        mockMvc.perform(delete("/api/v1/api-keys/inactive").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(0));
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/{id}", OWNERS_REVOKED_KEY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/api-keys/inactive"))
                .andExpect(status().isUnauthorized());

        assertThat(keyIds()).hasSize(6);
    }

    private void account(final long id, final String email, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password) values (?, ?, 'secret-hash')", id, email);
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
    }

    private void key(final long id, final long owner, final boolean active, final String expirationDate) {
        jdbcTemplate.update("""
                insert into api_key (id, "KEY", owner_id, api_calls_limit, api_calls_used, expiration_date, active)
                values (?, ?, ?, 100, 0, cast(? as date), ?)""", id, "key-" + id, owner, expirationDate, active);
    }

    private List<Long> keyIds() {
        return jdbcTemplate.queryForList("select id from api_key where owner_id in (?, ?, ?)", Long.class, OWNER, OTHER, ADMIN);
    }

    private void signedIn(final String token, final String email, final String role) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
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
    @EntityScan(basePackageClasses = ApiKey.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiKeyRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {ApiKeyRepository.class, UserRepository.class}))
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
            ApiKeyController.class,
            DefaultApiKeyService.class,
            DefaultApiKeyValidator.class,
            ApiKeyUpdateDtoToApiKeyConverter.class
    })
    static class TestApplication {
    }

}
