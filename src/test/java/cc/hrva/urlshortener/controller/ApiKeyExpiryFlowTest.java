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
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.context.annotation.Bean;
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
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ApiKeyExpiryFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:api-key-expiry-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class ApiKeyExpiryFlowTest {

    private static final long OWNER = 900L;
    private static final long OTHER = 901L;
    private static final long ADMIN = 902L;
    private static final long OWNERS_KEY = 910L;
    private static final String OWNER_EMAIL = "expiry-owner@hrva.cc";
    private static final String OTHER_EMAIL = "expiry-other@hrva.cc";
    private static final String ADMIN_EMAIL = "expiry-admin@hrva.cc";
    private static final String OWNER_TOKEN = "Bearer owner-token";
    private static final String OTHER_TOKEN = "Bearer other-token";
    private static final String ADMIN_TOKEN = "Bearer admin-token";
    private static final String CHOOSE_ONE = "Choose either an expiry in days or no expiry.";
    private static final String DAYS_RANGE = "Use a number of days from 1 to 3,650.";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final Instant LATE_EVENING = Instant.parse("2026-10-30T23:50:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 23, 50);
    private static final MutableClock CLOCK = new MutableClock(LATE_EVENING);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyValidator apiKeyValidator;

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
        CLOCK.set(LATE_EVENING);
        account(OWNER, OWNER_EMAIL, ROLE_USER);
        account(OTHER, OTHER_EMAIL, ROLE_USER);
        account(ADMIN, ADMIN_EMAIL, ROLE_USER, ROLE_ADMIN);
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (?, ?, 'owners', ?, 42, cast('2027-01-01' as date), true)""", OWNERS_KEY, ApiKeyTokens.hash("owners-expiry-key"), OWNER);
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
    void aNewKeyWithoutABodyExpiresAfterTheConfiguredYear() throws Exception {
        final var id = idOf(create(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiCallsUsed").value(0))
                .andExpect(jsonPath("$.lastUsedAt").doesNotExist())
                .andExpect(jsonPath("$.apiCallsLimit").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE")));

        assertThat(expiryOf(id)).isEqualTo(NOW.plusMonths(12));
    }

    @Test
    void aNewKeyIsShownOnceAndStoredOnlyAsItsHash() throws Exception {
        final var answer = create(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.matchesPattern("hrva_[0-9A-Za-z]{43}")))
                .andReturn().getResponse().getContentAsString();
        final String key = com.jayway.jsonpath.JsonPath.read(answer, "$.key");
        final long id = ((Number) com.jayway.jsonpath.JsonPath.read(answer, "$.id")).longValue();

        assertThat(com.jayway.jsonpath.JsonPath.<String>read(answer, "$.keyPrefix")).isEqualTo(key.substring(0, 11));
        assertThat(jdbcTemplate.queryForMap("select key_hash, key_prefix from api_key where id = ?", id))
                .containsEntry("KEY_HASH", ApiKeyTokens.hash(key))
                .containsEntry("KEY_PREFIX", key.substring(0, 11));
        assertThat(jdbcTemplate.queryForObject("select count(*) from information_schema.columns where upper(table_name) = 'API_KEY' and upper(column_name) = 'KEY'",
                Long.class)).isZero();
        mockMvc.perform(get("/api/v1/api-keys").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").isEmpty())
                .andExpect(jsonPath("$[*].keyPrefix").value(org.hamcrest.Matchers.hasItem(key.substring(0, 11))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(key))));
        mockMvc.perform(get("/api/v1/api-keys/all").param("search", key).header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains((int) id)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(key))));
    }

    @Test
    void aNewKeyWithAnEmptyJsonBodyGetsTheDefaultToo() throws Exception {
        final var id = idOf(create("{}").andExpect(status().isOk()));

        assertThat(expiryOf(id)).isEqualTo(NOW.plusMonths(12));
    }

    @Test
    void aNewKeyExpiresAfterTheChosenDays() throws Exception {
        final var id = idOf(create("{\"expiresInDays\":30}").andExpect(status().isOk()));

        assertThat(expiryOf(id)).isEqualTo(NOW.plusDays(30));
    }

    @Test
    void aNewKeyCanNeverExpire() throws Exception {
        final var id = idOf(create("{\"neverExpires\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationDate").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE")));

        assertThat(expiryOf(id)).isNull();
    }

    @Test
    void daysOutOfRangeAreRefusedOnTheirField() throws Exception {
        for (final var days : List.of("0", "3651", "-5")) {
            create("{\"expiresInDays\":" + days + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"expiresInDays\":\"" + DAYS_RANGE + "\"}", JsonCompareMode.STRICT));
        }
        create("{\"expiresInDays\":3650}").andExpect(status().isOk());
        create("{\"expiresInDays\":1}").andExpect(status().isOk());
    }

    @Test
    void daysAndNoExpiryTogetherAreRefused() throws Exception {
        create("{\"expiresInDays\":30,\"neverExpires\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"" + CHOOSE_ONE + "\"}", JsonCompareMode.STRICT));

        assertThat(keysOf(OWNER)).isOne();
    }

    @Test
    void theOwnerChangesTheExpiryToNever() throws Exception {
        update(OWNERS_KEY, OWNER_TOKEN, "{\"neverExpires\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationDate").doesNotExist())
                .andExpect(jsonPath("$.apiCallsUsed").value(42));

        assertThat(expiryOf(OWNERS_KEY)).isNull();
    }

    @Test
    void theOwnerChangesTheExpiryToSomeDays() throws Exception {
        update(OWNERS_KEY, OWNER_TOKEN, "{\"expiresInDays\":90}").andExpect(status().isOk());

        assertThat(expiryOf(OWNERS_KEY)).isEqualTo(NOW.plusDays(90));
    }

    @Test
    void anUpdateRefusesTheSameWayACreateDoes() throws Exception {
        update(OWNERS_KEY, OWNER_TOKEN, "{\"expiresInDays\":30,\"neverExpires\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"" + CHOOSE_ONE + "\"}", JsonCompareMode.STRICT));
        update(OWNERS_KEY, OWNER_TOKEN, "{\"expiresInDays\":4000}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"expiresInDays\":\"" + DAYS_RANGE + "\"}", JsonCompareMode.STRICT));

        assertThat(expiryOf(OWNERS_KEY)).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    @Test
    void theOldExpirationDateStillWorksForAFutureDateOnly() throws Exception {
        final var future = LocalDateTime.of(2028, 10, 30, 0, 0);
        update(OWNERS_KEY, ADMIN_TOKEN, "{\"expirationDate\":\"2028-10-30T00:00:00\"}").andExpect(status().isOk());
        assertThat(expiryOf(OWNERS_KEY)).isEqualTo(future);

        update(OWNERS_KEY, ADMIN_TOKEN, "{\"expirationDate\":\"2020-01-01T00:00:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"expirationDate\":\"Choose a date in the future.\"}", JsonCompareMode.STRICT));
        assertThat(expiryOf(OWNERS_KEY)).isEqualTo(future);
    }

    @Test
    void theCreateAnswerAndALaterListAgreeOnTheExpiryToTheSecond() throws Exception {
        CLOCK.set(Instant.parse("2026-10-30T23:50:07.123456789Z"));
        final var answer = create("{\"expiresInDays\":1}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final long id = ((Number) com.jayway.jsonpath.JsonPath.read(answer, "$.id")).longValue();

        assertThat(com.jayway.jsonpath.JsonPath.<String>read(answer, "$.expirationDate")).isEqualTo("2026-10-31T23:50:07");
        mockMvc.perform(get("/api/v1/api-keys").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].expirationDate").value("2026-10-31T23:50:07"));
        assertThat(expiryOf(id)).isEqualTo(LocalDateTime.of(2026, 10, 31, 23, 50, 7));
    }

    @Test
    void aOneDayKeyMadeLateInTheEveningWorksForAWholeDay() throws Exception {
        final var key = com.jayway.jsonpath.JsonPath.<String>read(create("{\"expiresInDays\":1}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.key");

        CLOCK.set(Instant.parse("2026-10-30T23:59:00Z"));
        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key)).doesNotThrowAnyException();
        CLOCK.set(Instant.parse("2026-10-31T12:00:00Z"));
        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key)).doesNotThrowAnyException();
        CLOCK.set(Instant.parse("2026-10-31T23:49:59Z"));
        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key)).doesNotThrowAnyException();
        CLOCK.set(Instant.parse("2026-10-31T23:50:01Z"));
        assertThatThrownBy(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key)).hasMessage("API key has expired");
    }

    @Test
    void anExpirationDateLaterTodayKeepsTheKeyAliveUntilThatTime() throws Exception {
        CLOCK.set(Instant.parse("2026-10-30T10:00:00Z"));
        final var key = com.jayway.jsonpath.JsonPath.<String>read(create(null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.key");
        final var id = jdbcTemplate.queryForObject("select id from api_key where key_hash = ?", Long.class, ApiKeyTokens.hash(key));

        update(id, OWNER_TOKEN, "{\"expirationDate\":\"2026-10-30T18:00:00\"}").andExpect(status().isOk());

        assertThat(expiryOf(id)).isEqualTo(LocalDateTime.of(2026, 10, 30, 18, 0));
        CLOCK.set(Instant.parse("2026-10-30T17:59:00Z"));
        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key)).doesNotThrowAnyException();
        CLOCK.set(Instant.parse("2026-10-30T18:01:00Z"));
        assertThatThrownBy(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key)).hasMessage("API key has expired");
    }

    @Test
    void anExpirationDateEarlierTodayIsRefused() throws Exception {
        update(OWNERS_KEY, OWNER_TOKEN, "{\"expirationDate\":\"2026-10-30T23:00:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"expirationDate\":\"Choose a date in the future.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void theCallLimitAndTheUsageCanNoLongerBeEdited() throws Exception {
        update(OWNERS_KEY, ADMIN_TOKEN, "{\"apiCallsLimit\":5,\"apiCallsUsed\":0,\"active\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiCallsUsed").value(42))
                .andExpect(jsonPath("$.apiCallsLimit").doesNotExist());

        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, OWNERS_KEY)).isEqualTo(42L);
    }

    @Test
    void someoneElsesKeyCannotBeUpdated() throws Exception {
        update(OWNERS_KEY, OTHER_TOKEN, "{\"neverExpires\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You don't have authorization for this action"));

        assertThat(expiryOf(OWNERS_KEY)).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    @Test
    void nobodySignedInCannotUpdate() throws Exception {
        mockMvc.perform(put("/api/v1/api-keys/{id}", OWNERS_KEY).contentType(MediaType.APPLICATION_JSON).content("{\"neverExpires\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keysThatNeverExpireComeLastWhenSortedByExpiryEitherWay() throws Exception {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (911, '911', 'never-', ?, 0, null, true)""", OWNER);
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (912, '912', 'expire', ?, 0, cast('2028-01-01' as date), true)""", OWNER);

        mockMvc.perform(get("/api/v1/api-keys/all").param("search", OWNER_EMAIL).param("sort", "expirationDate,asc").header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(910, 912, 911)));
        mockMvc.perform(get("/api/v1/api-keys/all").param("search", OWNER_EMAIL).param("sort", "expirationDate,desc").header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(912, 910, 911)));
    }

    @Test
    void keysSortByTheirLastUseWithTheNeverUsedLast() throws Exception {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active, last_used_at)
                values (911, '911', 'used-y', ?, 3, null, true, ?)""", OWNER, LocalDate.now().minusDays(1).atStartOfDay());
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active, last_used_at)
                values (912, '912', 'used-t', ?, 1, null, true, ?)""", OWNER, LocalDate.now().atStartOfDay());

        mockMvc.perform(get("/api/v1/api-keys/all").param("search", OWNER_EMAIL).param("sort", "lastUsedAt,desc").header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(912, 911, 910)))
                .andExpect(jsonPath("$.content[0].lastUsedAt").exists());
        mockMvc.perform(get("/api/v1/api-keys/all").param("search", OWNER_EMAIL).param("sort", "lastUsedAt,asc").header(JwtFilter.AUTHORIZATION_HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(911, 912, 910)));
    }

    private ResultActions create(final String body) throws Exception {
        final var request = post("/api/v1/api-keys").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions update(final long id, final String token, final String body) throws Exception {
        return mockMvc.perform(put("/api/v1/api-keys/{id}", id).header(JwtFilter.AUTHORIZATION_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"id\":" + id + "," + body.substring(1)));
    }

    private long idOf(final ResultActions created) throws Exception {
        return ((Number) com.jayway.jsonpath.JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private LocalDateTime expiryOf(final long id) {
        return jdbcTemplate.queryForObject("select expiration_date from api_key where id = ?", LocalDateTime.class, id);
    }

    private Long keysOf(final long owner) {
        return jdbcTemplate.queryForObject("select count(*) from api_key where owner_id = ?", Long.class, owner);
    }

    private void account(final long id, final String email, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password, api_key_slots) values (?, ?, 'secret-hash', 10)", id, email);
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
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

        @Bean
        Clock clock() {
            return CLOCK;
        }

    }

}
