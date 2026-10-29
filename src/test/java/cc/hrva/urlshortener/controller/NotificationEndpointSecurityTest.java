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
import cc.hrva.urlshortener.client.ChannelPoster;
import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.LastDeliveryStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultNotificationChannelService;
import cc.hrva.urlshortener.service.impl.DefaultNotificationConsentService;
import cc.hrva.urlshortener.service.notification.ChannelTargets;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationKeys;
import cc.hrva.urlshortener.service.notification.NotificationTestKeys;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationPayloads;
import cc.hrva.urlshortener.service.notification.SecretBox;
import cc.hrva.urlshortener.service.notification.channel.ChannelSenders;
import cc.hrva.urlshortener.service.notification.channel.DiscordChannelSender;
import cc.hrva.urlshortener.service.notification.channel.NtfyChannelSender;
import cc.hrva.urlshortener.service.notification.channel.SlackChannelSender;
import cc.hrva.urlshortener.service.notification.channel.TelegramChannelSender;
import cc.hrva.urlshortener.service.notification.channel.WebhookChannelSender;
import cc.hrva.urlshortener.service.notification.UnsubscribeTokens;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.InetAddress;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private static final String CHANNELS = "/api/v1/users/me/notification-channels";
    private static final String BULLETS = Character.toString(0x2022).repeat(4);
    private static final String DISCORD_CHANNEL = """
            {"name":"Team alerts","type":"DISCORD","enabled":true,"events":["WEEKLY_SUMMARY","LINK_DEAD"],
             "target":"https://discord.com/api/webhooks/123/abc-secret-xyz","botToken":null,"chatId":null,"accessToken":null}""";
    private static final String CHANNEL_NOT_FOUND = "{\"error\":\"Notification channel not found\"}";
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

    @Autowired
    private NotificationChannelRepository notificationChannelRepository;

    @Autowired
    private SecretBox secretBox;

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
        jdbcTemplate.update("delete from notification_channel where user_id in (?, ?)", ANA, BOB);
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

    @Test
    void theChannelEndpointsNeedSignIn() throws Exception {
        mockMvc.perform(get(CHANNELS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CHANNELS).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(CHANNELS + "/1").contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(CHANNELS + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CHANNELS + "/1/signing-secret")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CHANNELS + "/1/test")).andExpect(status().isUnauthorized());

        assertThat(notificationChannelRepository.count()).isZero();
    }

    @Test
    void aSignedInUserAddsListsEditsAndDeletesAChannel() throws Exception {
        final var created = mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Team alerts"))
                .andExpect(jsonPath("$.type").value("DISCORD"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.events[0]").value("LINK_DEAD"))
                .andExpect(jsonPath("$.events[1]").value("WEEKLY_SUMMARY"))
                .andExpect(jsonPath("$.maskedTarget").value("https://discord.com/" + BULLETS + "-xyz"))
                .andExpect(jsonPath("$.chatId").value(nullValue()))
                .andExpect(jsonPath("$.hasAccessToken").value(false))
                .andExpect(jsonPath("$.autoDisabledAt").value(nullValue()))
                .andExpect(jsonPath("$.lastDelivery").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.signingSecret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("abc-secret-xyz");
        final var id = notificationChannelRepository.findAll().getFirst().getId();
        assertThat(jdbcTemplate.queryForList("select type from notification_consent where channel_id = ? and revoked_at is null order by type", String.class, id))
                .containsExactly("LINK_DEAD", "WEEKLY_SUMMARY");
        assertThat(jdbcTemplate.queryForObject("select channel_label from notification_consent where channel_id = ? and type = 'LINK_DEAD'", String.class, id))
                .isEqualTo("Discord: Team alerts");

        mockMvc.perform(as(ANA_TOKEN, get(CHANNELS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].events.length()").value(2))
                .andExpect(jsonPath("$[0].signingSecret").doesNotExist());
        mockMvc.perform(as(BOB_TOKEN, get(CHANNELS)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));

        mockMvc.perform(as(ANA_TOKEN, put(CHANNELS + "/" + id)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Renamed","type":"DISCORD","enabled":false,"events":["LINK_BACK"],"chatId":null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0]").value("LINK_BACK"))
                .andExpect(jsonPath("$.maskedTarget").value("https://discord.com/" + BULLETS + "-xyz"));
        assertThat(jdbcTemplate.queryForList("select type from notification_consent where channel_id = ? and revoked_at is null", String.class, id))
                .containsExactly("LINK_BACK");

        mockMvc.perform(as(ANA_TOKEN, delete(CHANNELS + "/" + id)))
                .andExpect(status().isNoContent());
        assertThat(notificationChannelRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForList("select revoked_source from notification_consent where user_id = ? and revoked_at is not null and channel_id is null "
                + "and type = 'LINK_BACK'", String.class, ANA)).containsExactly("CHANNEL_REMOVED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification_consent where user_id = ? and revoked_at is null", Long.class, ANA)).isZero();
    }

    @Test
    void aTrailingDotOnTheHostIsNotStored() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(
                        DISCORD_CHANNEL.replace("https://discord.com/", "https://discord.com./")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedTarget").value("https://discord.com/" + Character.toString(0x2022).repeat(4) + "-xyz"));

        final var stored = notificationChannelRepository.findAll().getFirst();
        assertThat(secretBox.open(stored.getTargetSealed(), SecretBox.contextFor(stored.getOwner().getId())))
                .isEqualTo("https://discord.com/api/webhooks/123/abc-secret-xyz");
    }

    @Test
    void anEditAnswersWithTheUpdatedAtItStored() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();
        final var longAgo = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        jdbcTemplate.update("update notification_channel set updated_at = ? where id = ?", longAgo, id);

        final var renamed = updatedAtAfterEdit(id, """
                {"name":"Renamed","type":"DISCORD","events":["LINK_DEAD","WEEKLY_SUMMARY"]}""");
        final var storedAfterRename = storedUpdatedAt(id);
        jdbcTemplate.update("update notification_channel set updated_at = ? where id = ?", longAgo, id);
        final var eventsOnly = updatedAtAfterEdit(id, """
                {"name":"Renamed","type":"DISCORD","events":["LINK_BACK"]}""");

        assertThat(storedAfterRename).isAfter(longAgo);
        assertThat(renamed).isEqualTo(storedAfterRename);
        assertThat(storedUpdatedAt(id)).isAfter(longAgo);
        assertThat(eventsOnly).isEqualTo(storedUpdatedAt(id));
    }

    private LocalDateTime updatedAtAfterEdit(final long id, final String body) throws Exception {
        final var response = mockMvc.perform(as(ANA_TOKEN, put(CHANNELS + "/" + id)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return LocalDateTime.parse(com.jayway.jsonpath.JsonPath.read(response, "$.updatedAt"));
    }

    private LocalDateTime storedUpdatedAt(final long id) {
        return jdbcTemplate.queryForObject("select updated_at from notification_channel where id = ?", LocalDateTime.class, id);
    }

    @Test
    void channelFieldErrorsComeBackByField() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("\"Team alerts\"", "\" \"")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"name\":\"Give the channel a name\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON)
                        .content(DISCORD_CHANNEL.replace("Team alerts", "x".repeat(61))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"name\":\"Name must be 60 characters or fewer\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("\"DISCORD\"", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"type\":\"Choose a channel type\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("https://", "http://")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"target\":\"Use an https URL.\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"TELEGRAM","events":[],"botToken":"nope","chatId":"nope"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"botToken":"Paste the bot token from @BotFather, like 123456789:AAH4Fq2xYzExample",
                         "chatId":"Enter the chat ID, a number like -1001234567890 or a channel name like @mychannel"}""", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("WEEKLY_SUMMARY", "WEEKLY_SUMMARY_EMAIL")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Events must be LINK_DEAD, LINK_BACK, DAILY_SUMMARY or WEEKLY_SUMMARY\"}", JsonCompareMode.STRICT));

        assertThat(notificationChannelRepository.count()).isZero();
    }

    @Test
    void everyChannelFieldErrorComesBackInOneAnswer() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":" ","type":"NTFY","events":[],"target":"http://ntfy.sh/alerts","accessToken":"tk abc"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"name":"Give the channel a name",
                         "target":"Use an https URL.",
                         "accessToken":"Paste the access token from ntfy. It has no spaces, like tk_AbC123"}""", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"%s","type":"TELEGRAM","events":[],"botToken":"nope","chatId":"nope"}""".formatted("x".repeat(61))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"name":"Name must be 60 characters or fewer",
                         "botToken":"Paste the bot token from @BotFather, like 123456789:AAH4Fq2xYzExample",
                         "chatId":"Enter the chat ID, a number like -1001234567890 or a channel name like @mychannel"}""", JsonCompareMode.STRICT));

        assertThat(notificationChannelRepository.count()).isZero();
    }

    @Test
    void anEditReportsEveryFieldErrorInOneAnswer() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"NTFY","events":[],"target":"https://ntfy.sh/alerts"}"""))
                .andExpect(status().isCreated());
        final var channel = notificationChannelRepository.findAll().getFirst();

        mockMvc.perform(as(ANA_TOKEN, put(CHANNELS + "/" + channel.getId())).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","type":"NTFY","events":[],"target":"https://ntfy.sh/","accessToken":"tk abc"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"name":"Give the channel a name",
                         "target":"Paste your ntfy topic URL, like https://ntfy.sh/your-topic",
                         "accessToken":"Paste the access token from ntfy. It has no spaces, like tk_AbC123"}""", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, put(CHANNELS + "/" + channel.getId())).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","type":null,"events":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"name\":\"Give the channel a name\",\"type\":\"Choose a channel type\"}", JsonCompareMode.STRICT));

        assertThat(notificationChannelRepository.findById(channel.getId()).orElseThrow().getName()).isEqualTo("Phone");
    }

    @Test
    void anEditWithoutANewTopicStillChecksTheNtfyAccessToken() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"NTFY","events":[],"target":"https://ntfy.sh/alerts","accessToken":"tk_first"}"""))
                .andExpect(status().isCreated());
        final var channel = notificationChannelRepository.findAll().getFirst();

        mockMvc.perform(as(ANA_TOKEN, put(CHANNELS + "/" + channel.getId())).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"NTFY","events":[],"target":"","accessToken":"tk abc"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"accessToken\":\"Paste the access token from ntfy. It has no spaces, like tk_AbC123\"}",
                        JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, put(CHANNELS + "/" + channel.getId())).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"NTFY","events":[],"target":"","accessToken":" "}"""))
                .andExpect(status().isOk());

        assertThat(notificationChannelRepository.findById(channel.getId()).orElseThrow().getAccessTokenSealed())
                .isEqualTo(channel.getAccessTokenSealed());
    }

    @Test
    void overlongChannelFieldsComeBackByField() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON)
                        .content(DISCORD_CHANNEL.replace("abc-secret-xyz", "a".repeat(2049 - "https://discord.com/api/webhooks/123/".length()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"target\":\"URL must be 2048 characters or fewer\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"TELEGRAM","events":[],"botToken":"%s","chatId":"%s"}""".formatted("1".repeat(256), "1".repeat(41))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"botToken":"Bot token must be 255 characters or fewer",
                         "chatId":"Chat ID must be 40 characters or fewer"}""", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"NTFY","events":[],"target":"https://ntfy.sh/alerts","accessToken":"%s"}""".formatted("t".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"accessToken\":\"Access token must be 255 characters or fewer\"}", JsonCompareMode.STRICT));

        assertThat(notificationChannelRepository.count()).isZero();
    }

    @Test
    void channelFieldsAtTheirLimitsAreAccepted() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON)
                        .content(DISCORD_CHANNEL.replace("abc-secret-xyz", "a".repeat(2048 - "https://discord.com/api/webhooks/123/".length()))))
                .andExpect(status().isCreated());
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Phone","type":"NTFY","events":[],"target":"https://ntfy.sh/alerts","accessToken":"%s"}""".formatted("t".repeat(255))))
                .andExpect(status().isCreated());
    }

    @Test
    void theLastDeliveryStatusReadsSentOrFailed() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();

        for (final var status : List.of("SENT", "FAILED")) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> notificationChannelRepository.recordDelivery(id,
                    LocalDateTime.of(2026, 10, 27, 2, 14, 9), LastDeliveryStatus.valueOf(status), 404, null));

            assertThat(jdbcTemplate.queryForObject("select last_delivery_status from notification_channel where id = ?", String.class, id))
                    .isEqualTo(status);
            mockMvc.perform(as(ANA_TOKEN, get(CHANNELS)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].lastDelivery.status").value(status));
        }
    }

    @Test
    void theEleventhChannelIsRefused() throws Exception {
        for (int existing = 0; existing < 10; existing++) {
            jdbcTemplate.update("""
                    insert into notification_channel (user_id, name, type, enabled, target_sealed, masked_target, failed_days, created_at, updated_at)
                    values (?, ?, 'NTFY', true, 'v1:x', 'https://ntfy.sh/x', 0, ?, ?)""",
                    ANA, "Channel " + existing, LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 20, 9, 0));
        }

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"You can have up to 10 channels. Remove one to add another.\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(BOB_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isCreated());
    }

    @Test
    void someoneElsesChannelIs404() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("DISCORD", "WEBHOOK")
                        .replace("discord.com/api/webhooks/123", "hooks.example.org/hrva")))
                .andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();

        mockMvc.perform(as(BOB_TOKEN, put(CHANNELS + "/" + id)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isNotFound())
                .andExpect(content().json(CHANNEL_NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(BOB_TOKEN, delete(CHANNELS + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(content().json(CHANNEL_NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(BOB_TOKEN, post(CHANNELS + "/" + id + "/signing-secret")))
                .andExpect(status().isNotFound())
                .andExpect(content().json(CHANNEL_NOT_FOUND, JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, delete(CHANNELS + "/999999")))
                .andExpect(status().isNotFound())
                .andExpect(content().json(CHANNEL_NOT_FOUND, JsonCompareMode.STRICT));
        assertThat(notificationChannelRepository.count()).isEqualTo(1L);
    }

    @Test
    void sendTestAnswersWithTheOutcomeAndRecordsIt() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL)).andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS + "/" + id + "/test")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"delivered":false,"httpStatus":404,"error":"Discord doesn't know this webhook. It may have been deleted. Copy the webhook URL again."}""", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, get(CHANNELS)))
                .andExpect(jsonPath("$[0].lastDelivery.status").value("FAILED"))
                .andExpect(jsonPath("$[0].lastDelivery.at").isString())
                .andExpect(jsonPath("$[0].lastDelivery.httpStatus").value(404))
                .andExpect(jsonPath("$[0].lastDelivery.error").value("Discord doesn't know this webhook. It may have been deleted. Copy the webhook URL again."));
        assertThat(jdbcTemplate.queryForObject("select failed_days from notification_channel where id = ?", Integer.class, id)).isZero();
        mockMvc.perform(as(BOB_TOKEN, post(CHANNELS + "/" + id + "/test")))
                .andExpect(status().isNotFound())
                .andExpect(content().json(CHANNEL_NOT_FOUND, JsonCompareMode.STRICT));
    }

    @Test
    void aWebhookSigningSecretIsShownOnCreateAndRotate() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("DISCORD", "WEBHOOK")
                        .replace("discord.com/api/webhooks/123", "hooks.example.org/hrva")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signingSecret").isString());
        final var id = notificationChannelRepository.findAll().getFirst().getId();

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS + "/" + id + "/signing-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecret").isString())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(as(ANA_TOKEN, get(CHANNELS)))
                .andExpect(jsonPath("$[0].signingSecret").doesNotExist());
    }

    @Test
    void aChatChannelHasNoSigningSecret() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL)).andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS + "/" + id + "/signing-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Only webhook channels have a signing secret.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void channelChangesOverTheLimitAre429() throws Exception {
        when(rateLimiter.tryAcquire(eq("notify-settings:user:" + ANA), eq(60), eq(Duration.ofHours(1)))).thenReturn(false);

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("""
                        {"error":"You've changed your notification settings a lot in a short time. Try again in a few minutes."}""", JsonCompareMode.STRICT));
        assertThat(notificationChannelRepository.count()).isZero();
    }

    @Test
    void signingSecretsOverTheTestLimitAre429() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("DISCORD", "WEBHOOK")
                        .replace("discord.com/api/webhooks/123", "hooks.example.org/hrva")))
                .andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();
        when(rateLimiter.tryAcquire(eq("notify-test:user:" + ANA), eq(10), eq(Duration.ofMinutes(10)))).thenReturn(false);

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS + "/" + id + "/signing-secret")))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("""
                        {"error":"You've sent a lot of tests in a short time. Try again in a few minutes."}""", JsonCompareMode.STRICT));
    }

    @Test
    void signingSecretsAre503WhenTheTestLimiterIsDown() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS)).contentType(MediaType.APPLICATION_JSON).content(DISCORD_CHANNEL.replace("DISCORD", "WEBHOOK")
                        .replace("discord.com/api/webhooks/123", "hooks.example.org/hrva")))
                .andExpect(status().isCreated());
        final var id = notificationChannelRepository.findAll().getFirst().getId();
        when(rateLimiter.tryAcquire(eq("notify-test:user:" + ANA), eq(10), eq(Duration.ofMinutes(10))))
                .thenThrow(new cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException(new IllegalStateException("redis down")));

        mockMvc.perform(as(ANA_TOKEN, post(CHANNELS + "/" + id + "/signing-secret")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                        {"error":"Sending a test isn't available right now. Try again in a minute."}""", JsonCompareMode.STRICT));
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
                    classes = {UserRepository.class, NotificationConsentRepository.class, NotificationChannelRepository.class}))
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
            NotificationKeys.class,
            NotificationChannelController.class,
            DefaultNotificationChannelService.class,
            ChannelTargets.class,
            SecretBox.class,
            ChannelSenders.class,
            DiscordChannelSender.class,
            SlackChannelSender.class,
            TelegramChannelSender.class,
            NtfyChannelSender.class,
            WebhookChannelSender.class,
            NotificationMessages.class,
            NotificationPayloads.class,
            TestApplication.PublicDns.class
    })
    static class TestApplication {

        @TestConfiguration
        static class PublicDns {

            @Bean
            Resolver resolver() {
                return host -> new InetAddress[] {InetAddress.ofLiteral("162.159.128.233")};
            }

            @Bean
            ChannelPoster channelPoster() {
                return (url, contentType, headers, body, timeout) -> new PostResult(404, null);
            }

        }

    }

}
