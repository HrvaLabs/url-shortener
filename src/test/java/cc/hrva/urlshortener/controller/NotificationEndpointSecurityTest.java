package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultNotificationConsentService;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationKeys;
import cc.hrva.urlshortener.service.notification.NotificationTestKeys;
import cc.hrva.urlshortener.service.notification.UnsubscribeTokens;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = NotificationEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:notification-endpoints;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost",
                NotificationTestKeys.PROPERTY
        })
@AutoConfigureMockMvc
class NotificationEndpointSecurityTest {

    private static final String NOTIFICATIONS = "/api/v1/users/me/notifications";
    private static final String UNSUBSCRIBE = "/api/v1/notifications/unsubscribe";
    private static final String INVALID_LINK = """
            {"error":"This unsubscribe link isn't valid. You can turn emails off in Settings, under Notifications."}""";
    private static final long ANA = 600L;
    private static final long BOB = 601L;
    private static final String ANA_EMAIL = "ana@hrva.cc";
    private static final String BOB_EMAIL = "bob@hrva.cc";
    private static final String ANA_TOKEN = "ana-token";
    private static final String BOB_TOKEN = "bob-token";
    private static final long ROLE_USER = 1L;
    private static final String ALL_OFF = """
            [{"type":"DEAD_LINK_EMAIL","consented":false,"grantedAt":null,"description":"Email me when one of my links stops working. Links are checked every night."},
             {"type":"WEEKLY_SUMMARY_EMAIL","consented":false,"grantedAt":null,"description":"A summary of last week's clicks every Monday morning."},
             {"type":"EXPIRY_REMINDER_EMAIL","consented":false,"grantedAt":null,"description":"Email me a day before one of my links expires."}]""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationConsentRepository notificationConsentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UnsubscribeTokens unsubscribeTokens;

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
        account(ANA, ANA_EMAIL);
        account(BOB, BOB_EMAIL);
        signedIn(ANA_TOKEN, ANA_EMAIL);
        signedIn(BOB_TOKEN, BOB_EMAIL);
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from notification_consent where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, BOB);
    }

    @Test
    void theEmailSettingsNeedSignIn() throws Exception {
        mockMvc.perform(get(NOTIFICATIONS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(NOTIFICATIONS + "/WEEKLY_SUMMARY_EMAIL").contentType(MediaType.APPLICATION_JSON).content("{\"consented\":true}"))
                .andExpect(status().isUnauthorized());

        assertThat(notificationConsentRepository.count()).isZero();
    }

    @Test
    void aNewUserHasEveryEmailOff() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, get(NOTIFICATIONS)))
                .andExpect(status().isOk())
                .andExpect(content().json(ALL_OFF, JsonCompareMode.STRICT));
    }

    @Test
    void aSignedInUserTurnsAnEmailOnAndOff() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/WEEKLY_SUMMARY_EMAIL")).contentType(MediaType.APPLICATION_JSON).content("{\"consented\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WEEKLY_SUMMARY_EMAIL"))
                .andExpect(jsonPath("$.consented").value(true))
                .andExpect(jsonPath("$.grantedAt").isString())
                .andExpect(jsonPath("$.description").value("A summary of last week's clicks every Monday morning."));
        mockMvc.perform(as(ANA_TOKEN, get(NOTIFICATIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].consented").value(true))
                .andExpect(jsonPath("$[0].consented").value(false));

        mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/WEEKLY_SUMMARY_EMAIL")).contentType(MediaType.APPLICATION_JSON).content("{\"consented\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consented").value(false))
                .andExpect(jsonPath("$.grantedAt").value(nullValue()));

        assertThat(notificationConsentRepository.findAll()).singleElement().satisfies(consent -> {
            assertThat(consent.getRevokedAt()).isNotNull();
            assertThat(consent.getGrantedAt()).isNotNull();
        });
    }

    @Test
    void turningOnTwiceKeepsOneRecord() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/DEAD_LINK_EMAIL")).contentType(MediaType.APPLICATION_JSON).content("{\"consented\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.consented").value(true));
        }

        assertThat(notificationConsentRepository.findAll()).singleElement().extracting(NotificationConsent::getRevokedAt).isNull();
    }

    @Test
    void aChannelEventIsNotAnEmailType() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/LINK_DEAD")).contentType(MediaType.APPLICATION_JSON).content("{\"consented\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":"Notification type must be DEAD_LINK_EMAIL, WEEKLY_SUMMARY_EMAIL or EXPIRY_REMINDER_EMAIL"}""", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/FOO")).contentType(MediaType.APPLICATION_JSON).content("{\"consented\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":"Notification type must be DEAD_LINK_EMAIL, WEEKLY_SUMMARY_EMAIL or EXPIRY_REMINDER_EMAIL"}""", JsonCompareMode.STRICT));

        assertThat(notificationConsentRepository.count()).isZero();
    }

    @Test
    void consentedIsRequired() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/DEAD_LINK_EMAIL")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"consented\":\"Choose on or off\"}", JsonCompareMode.STRICT));
    }

    @Test
    void eachUserSeesOnlyTheirOwnState() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, put(NOTIFICATIONS + "/EXPIRY_REMINDER_EMAIL")).contentType(MediaType.APPLICATION_JSON).content("{\"consented\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(BOB_TOKEN, get(NOTIFICATIONS)))
                .andExpect(status().isOk())
                .andExpect(content().json(ALL_OFF, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(NOTIFICATIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].type").value("EXPIRY_REMINDER_EMAIL"))
                .andExpect(jsonPath("$[2].consented").value(true));
        assertThat(notificationConsentRepository.findAll()).extracting(consent -> consent.getOwner().getId()).containsExactly(ANA);
    }

    @Test
    void getNeverRevokes() throws Exception {
        consentOf(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);
        final var token = unsubscribeTokens.issue(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);

        mockMvc.perform(get(UNSUBSCRIBE).param("token", token))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/unsubscribe?token=" + token));

        assertThat(isActive(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL)).isTrue();
    }

    @Test
    void oneClickPostRevokes() throws Exception {
        consentOf(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);
        final var token = unsubscribeTokens.issue(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);

        mockMvc.perform(oneClick(token))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"type\":\"WEEKLY_SUMMARY_EMAIL\",\"alreadyOff\":false}", JsonCompareMode.STRICT));

        assertThat(isActive(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL)).isFalse();
        assertThat(jdbcTemplate.queryForObject("select revoked_source from notification_consent where user_id = ?", String.class, ANA))
                .isEqualTo("UNSUBSCRIBE_LINK");
    }

    @Test
    void aDeactivatedAccountCanStillUnsubscribe() throws Exception {
        consentOf(ANA, NotificationType.EXPIRY_REMINDER_EMAIL);
        jdbcTemplate.update("update user_account set active = false where id = ?", ANA);

        mockMvc.perform(oneClick(unsubscribeTokens.issue(ANA, NotificationType.EXPIRY_REMINDER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"type\":\"EXPIRY_REMINDER_EMAIL\",\"alreadyOff\":false}", JsonCompareMode.STRICT));

        assertThat(isActive(ANA, NotificationType.EXPIRY_REMINDER_EMAIL)).isFalse();
    }

    @Test
    void unsubscribeTurnsOffOnlyTheNamedEmail() throws Exception {
        consentOf(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);
        consentOf(ANA, NotificationType.DEAD_LINK_EMAIL);
        consentOf(BOB, NotificationType.WEEKLY_SUMMARY_EMAIL);

        mockMvc.perform(post(UNSUBSCRIBE + "?token=" + unsubscribeTokens.issue(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(isActive(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL)).isFalse();
        assertThat(isActive(ANA, NotificationType.DEAD_LINK_EMAIL)).isTrue();
        assertThat(isActive(BOB, NotificationType.WEEKLY_SUMMARY_EMAIL)).isTrue();
    }

    @Test
    void aSecondPostIsAlreadyOff() throws Exception {
        consentOf(ANA, NotificationType.DEAD_LINK_EMAIL);
        final var token = unsubscribeTokens.issue(ANA, NotificationType.DEAD_LINK_EMAIL);
        mockMvc.perform(oneClick(token)).andExpect(status().isOk());

        mockMvc.perform(oneClick(token))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"type\":\"DEAD_LINK_EMAIL\",\"alreadyOff\":true}", JsonCompareMode.STRICT));
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification_consent where user_id = ?", Long.class, ANA)).isEqualTo(1L);
    }

    @Test
    void anUnknownUserIsAlreadyOff() throws Exception {
        mockMvc.perform(oneClick(unsubscribeTokens.issue(999L, NotificationType.EXPIRY_REMINDER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"type\":\"EXPIRY_REMINDER_EMAIL\",\"alreadyOff\":true}", JsonCompareMode.STRICT));
    }

    @Test
    void aStaleJwtDoesNotBlockIt() throws Exception {
        consentOf(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);

        mockMvc.perform(as("stale-token", oneClick(unsubscribeTokens.issue(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"type\":\"WEEKLY_SUMMARY_EMAIL\",\"alreadyOff\":false}", JsonCompareMode.STRICT));
        mockMvc.perform(as("stale-token", get(UNSUBSCRIBE).param("token", "x")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/unsubscribe?token=x"));
    }

    @Test
    void invalidTokenIs400() throws Exception {
        consentOf(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);
        final var valid = unsubscribeTokens.issue(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);

        for (final var token : List.of("x", valid + "x", valid.substring(1))) {
            mockMvc.perform(oneClick(token))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json(INVALID_LINK, JsonCompareMode.STRICT));
        }
        mockMvc.perform(post(UNSUBSCRIBE))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(INVALID_LINK, JsonCompareMode.STRICT));
        assertThat(isActive(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL)).isTrue();
    }

    @Test
    void overTheLimitIs429() throws Exception {
        consentOf(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL);
        when(rateLimiter.tryAcquire(startsWith("unsubscribe:"), eq(30), eq(Duration.ofMinutes(10)))).thenReturn(false);

        mockMvc.perform(oneClick(unsubscribeTokens.issue(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL)))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"Too many tries from your network. Try again in a few minutes.\"}", JsonCompareMode.STRICT));
        assertThat(isActive(ANA, NotificationType.WEEKLY_SUMMARY_EMAIL)).isTrue();
    }

    private static MockHttpServletRequestBuilder oneClick(final String token) {
        return post(UNSUBSCRIBE + "?token=" + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("List-Unsubscribe=One-Click");
    }

    private void consentOf(final long userId, final NotificationType type) {
        jdbcTemplate.update("insert into notification_consent (user_id, type, granted_at, source) values (?, ?, ?, 'SETTINGS')",
                userId, type.name(), LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    private boolean isActive(final long userId, final NotificationType type) {
        return jdbcTemplate.queryForObject("select count(*) from notification_consent where user_id = ? and type = ? and revoked_at is null",
                Long.class, userId, type.name()) == 1L;
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
                    classes = {UserRepository.class, NotificationConsentRepository.class}))
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            ClockConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            AccountLock.class,
            NotificationController.class,
            DefaultNotificationConsentService.class,
            EmailConsentReader.class,
            UnsubscribeController.class,
            UnsubscribeTokens.class,
            NotificationKeys.class
    })
    static class TestApplication {
    }

}
