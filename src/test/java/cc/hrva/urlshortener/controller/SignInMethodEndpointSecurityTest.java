package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.listener.SignInMethodChangedEmailListener;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.RecentSignIn;
import cc.hrva.urlshortener.security.StoredPasswords;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodChangeService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodService;
import cc.hrva.urlshortener.service.impl.DefaultUserDetailsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.signin.SignInMethodGuard;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.validator.impl.DefaultAuthValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SignInMethodEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:sign-in-method-endpoints;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class SignInMethodEndpointSecurityTest {

    private static final String METHODS = "/api/v1/users/me/sign-in-methods/";
    private static final String REMOVE_PASSWORD = "/api/v1/users/me/password/remove";
    private static final long ANA = 700L;
    private static final long BOB = 701L;
    private static final String ANA_EMAIL = "ana@hrva.cc";
    private static final String BOB_EMAIL = "bob@hrva.cc";
    private static final String ANA_TOKEN = "ana-token";
    private static final String BOB_TOKEN = "bob-token";
    private static final String BOB_FRESH_TOKEN = "bob-fresh-token";
    private static final String PASSWORD = "correct horse battery";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 5, 16, 10, 0);
    private static final LocalDateTime LAST = LocalDateTime.of(2026, 10, 29, 8, 0);
    private static final long ROLE_USER = 1L;
    private static final String KEEP_ONE = """
            {"error":"Keep at least one way to sign in. Add a passkey or another method first."}""";
    private static final String NOT_A_PROVIDER = """
            {"error":"Only Google and GitHub sign-in can be turned off or on here."}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        account(ANA, ANA_EMAIL, PASSWORD_HASH, "local");
        method(ANA, "PASSWORD");
        method(ANA, "GOOGLE");
        account(BOB, BOB_EMAIL, "6c1b2a3e-0b8e-4c1e-9d0e-1f2a3b4c5d6e", "google");
        method(BOB, "GOOGLE");
        signedIn(ANA_TOKEN, ANA_EMAIL);
        signedIn(BOB_TOKEN, BOB_EMAIL);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from passkey_credential where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_sign_in_method where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, BOB);
    }

    @Test
    void everyChangeNeedsSignIn() throws Exception {
        mockMvc.perform(post(METHODS + "GOOGLE/turn-off")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(METHODS + "GOOGLE/turn-on")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(REMOVE_PASSWORD).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(turnedOff(ANA)).isEmpty();
        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void turningGoogleOffAnswersTheUpdatedMethodsAndKeepsTheRow() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInMethods.length()").value(2))
                .andExpect(jsonPath("$.signInMethods[0].method").value("PASSWORD"))
                .andExpect(jsonPath("$.signInMethods[0].turnedOffAt").value(nullValue()))
                .andExpect(jsonPath("$.signInMethods[1].method").value("GOOGLE"))
                .andExpect(jsonPath("$.signInMethods[1].firstUsedAt").value("2026-05-16T10:00:00"))
                .andExpect(jsonPath("$.signInMethods[1].lastUsedAt").value("2026-10-29T08:00:00"))
                .andExpect(jsonPath("$.signInMethods[1].turnedOffAt").isString());

        assertThat(turnedOff(ANA)).containsExactly("GOOGLE");
        assertThat(auditRows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("PERFORMED_BY", ANA_EMAIL)
                .containsEntry("ACTION", "TURN_OFF_SIGN_IN_METHOD")
                .containsEntry("FAILED", false)
                .containsEntry("TARGET_TYPE", "User")
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(ANA))
                .containsEntry("DETAILS", "method: GOOGLE"));
        final var user = ArgumentCaptor.forClass(User.class);
        verify(sendingEmailService).sendSignInMethodChangedEmail(user.capture(), eq("Google sign-in was turned off."), any(LocalDateTime.class));
        assertThat(user.getValue().getEmail()).isEqualTo(ANA_EMAIL);
    }

    @Test
    void turningOffWhatIsAlreadyOffChangesNothingMore() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());
        final var firstTurnedOffAt = turnedOffAt(ANA, "GOOGLE");

        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInMethods[1].turnedOffAt").isString());

        assertThat(turnedOffAt(ANA, "GOOGLE")).isEqualTo(firstTurnedOffAt);
        assertThat(auditActions()).containsExactly("TURN_OFF_SIGN_IN_METHOD");
        verify(sendingEmailService, times(1)).sendSignInMethodChangedEmail(any(), anyString(), any());
    }

    @Test
    void turningGoogleBackOnClearsTheTimeAndIsAuditedAndEmailed() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-on")).contentType(MediaType.APPLICATION_JSON).content(reauthBody(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInMethods[1].method").value("GOOGLE"))
                .andExpect(jsonPath("$.signInMethods[1].turnedOffAt").value(nullValue()));
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-on")).contentType(MediaType.APPLICATION_JSON).content(reauthBody(PASSWORD)))
                .andExpect(status().isOk());

        assertThat(turnedOff(ANA)).isEmpty();
        assertThat(auditActions()).containsExactly("TURN_OFF_SIGN_IN_METHOD", "TURN_ON_SIGN_IN_METHOD");
        verify(sendingEmailService).sendSignInMethodChangedEmail(any(), eq("Google sign-in was turned back on."), any(LocalDateTime.class));
    }

    @Test
    void turningBackOnNeedsTheCurrentPasswordOfAnAccountThatHasOne() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-on")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Enter your current password.\"}", JsonCompareMode.STRICT));
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-on")).contentType(MediaType.APPLICATION_JSON).content(reauthBody("not it")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"Current password is incorrect\"}", JsonCompareMode.STRICT));

        assertThat(turnedOff(ANA)).containsExactly("GOOGLE");
        assertThat(auditActions()).containsExactly("TURN_OFF_SIGN_IN_METHOD");
        verify(loginAttemptService).loginFailed("reauth:" + ANA);
    }

    @Test
    void anAccountWithoutAPasswordTurnsBackOnOnlyAfterARecentSignIn() throws Exception {
        passkey(BOB, "bob-synced", true);
        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-on")).contentType(MediaType.APPLICATION_JSON).content(reauthBody(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"For your security, sign in again to change your sign-in methods.\"}",
                        JsonCompareMode.STRICT));
        assertThat(turnedOff(BOB)).containsExactly("GOOGLE");

        signedInJustNow(BOB_FRESH_TOKEN, BOB_EMAIL);
        mockMvc.perform(as(BOB_FRESH_TOKEN, post(METHODS + "GOOGLE/turn-on")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInMethods[0].turnedOffAt").value(nullValue()));
        assertThat(turnedOff(BOB)).isEmpty();
    }

    @Test
    void turningOffNeedsNoReauthentication() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        verify(loginAttemptService, never()).isBlocked("reauth:" + ANA);
    }

    @Test
    void aProviderTheAccountNeverUsedIs404() throws Exception {
        for (final var action : List.of("turn-off", "turn-on")) {
            mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GITHUB/" + action)).contentType(MediaType.APPLICATION_JSON).content(reauthBody(PASSWORD)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json("{\"error\":\"This account has never signed in with GitHub.\"}", JsonCompareMode.STRICT));
        }

        assertThat(auditActions()).isEmpty();
        verify(sendingEmailService, never()).sendSignInMethodChangedEmail(any(), anyString(), any());
    }

    @Test
    void passwordAndPasskeysCantBeTurnedOffOrOnHere() throws Exception {
        method(ANA, "PASSKEY");
        for (final var method : List.of("PASSWORD", "PASSKEY")) {
            for (final var action : List.of("turn-off", "turn-on")) {
                mockMvc.perform(as(ANA_TOKEN, post(METHODS + method + "/" + action)))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().json(NOT_A_PROVIDER, JsonCompareMode.STRICT));
            }
        }
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "FACEBOOK/turn-off")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Invalid value for method\"}", JsonCompareMode.STRICT));

        assertThat(turnedOff(ANA)).isEmpty();
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void theLastWayInCantBeTurnedOff() throws Exception {
        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));

        assertThat(turnedOff(BOB)).isEmpty();
        assertThat(auditActions()).isEmpty();
        verify(sendingEmailService, never()).sendSignInMethodChangedEmail(any(), anyString(), any());
    }

    @Test
    void aPasskeyThatSignsInAloneIsAWayIn() throws Exception {
        passkey(BOB, "bob-synced", true);

        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        assertThat(turnedOff(BOB)).containsExactly("GOOGLE");
    }

    @Test
    void aPasskeyWhoseKindIsUnknownIsNotAWayIn() throws Exception {
        passkey(BOB, "bob-unknown", null);

        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));
    }

    @Test
    void aSecondStepOnlyKeyIsNotAWayIn() throws Exception {
        passkey(BOB, "bob-yubikey", false);

        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));
    }

    @Test
    void aWrongCurrentPasswordIsAFieldErrorAndCounted() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody("not it")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"currentPassword\":\"That password isn't right.\"}", JsonCompareMode.STRICT));

        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
        assertThat(turnedOff(ANA)).isEmpty();
        assertThat(auditActions()).isEmpty();
        verify(loginAttemptService).loginFailed("reauth:" + ANA);
        verify(sendingEmailService, never()).sendSignInMethodChangedEmail(any(), anyString(), any());
    }

    @Test
    void aMissingCurrentPasswordIsAFieldError() throws Exception {
        for (final var body : List.of("{}", passwordBody("  "))) {
            mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"currentPassword\":\"Enter your current password.\"}", JsonCompareMode.STRICT));
        }

        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void tooManyWrongPasswordsAreRefusedBeforeTheCompare() throws Exception {
        when(loginAttemptService.isBlocked("reauth:" + ANA)).thenReturn(true);

        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"Too many wrong passwords. Try again in 24 hours.\"}", JsonCompareMode.STRICT));

        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void theRightCurrentPasswordRemovesThePassword() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInMethods[0].method").value("PASSWORD"))
                .andExpect(jsonPath("$.signInMethods[0].turnedOffAt").isString())
                .andExpect(jsonPath("$.signInMethods[1].method").value("GOOGLE"))
                .andExpect(jsonPath("$.signInMethods[1].turnedOffAt").value(nullValue()));

        assertThat(passwordOf(ANA)).isNotEqualTo(PASSWORD_HASH).satisfies(stored -> assertThat(StoredPasswords.isSet(stored)).isFalse());
        assertThat(turnedOff(ANA)).containsExactly("PASSWORD");
        assertThat(auditRows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("PERFORMED_BY", ANA_EMAIL)
                .containsEntry("ACTION", "REMOVE_PASSWORD")
                .containsEntry("TARGET_TYPE", "User")
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(ANA))
                .containsEntry("DETAILS", "method: PASSWORD"));
        verify(sendingEmailService).sendSignInMethodChangedEmail(any(), eq("Your password was removed."), any(LocalDateTime.class));
    }

    @Test
    void anAccountWithoutAPasswordHasNoneToRemove() throws Exception {
        mockMvc.perform(as(BOB_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"This account has no password to remove.\"}", JsonCompareMode.STRICT));

        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isNotFound());
        assertThat(auditActions()).containsExactly("REMOVE_PASSWORD");
    }

    @Test
    void thePasswordCantBeRemovedWhenItIsTheLastWayIn() throws Exception {
        jdbcTemplate.update("delete from user_sign_in_method where user_id = ? and method = 'GOOGLE'", ANA);

        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));

        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
        assertThat(turnedOff(ANA)).isEmpty();
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void aTurnedOffProviderIsNotAWayIn() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));
        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void aRemovedPasswordIsNotAWayIn() throws Exception {
        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));
        assertThat(turnedOff(ANA)).containsExactly("PASSWORD");
    }

    @Test
    void withTwoStepOnAPasskeyAloneIsNotAFirstStep() throws Exception {
        jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", ANA);
        passkey(ANA, "ana-synced", true);
        mockMvc.perform(as(ANA_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));

        assertThat(passwordOf(ANA)).isEqualTo(PASSWORD_HASH);
        jdbcTemplate.update("update user_account set two_step_enabled = false where id = ?", ANA);
        mockMvc.perform(as(ANA_TOKEN, post(REMOVE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(passwordBody(PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void withTwoStepOnTheLastProviderCantBeTurnedOffEvenWithAPasskey() throws Exception {
        jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", BOB);
        passkey(BOB, "bob-synced", true);

        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off")))
                .andExpect(status().isConflict())
                .andExpect(content().json(KEEP_ONE, JsonCompareMode.STRICT));
    }

    @Test
    void oneAccountsChangeNeverTouchesAnother() throws Exception {
        passkey(BOB, "bob-synced", true);

        mockMvc.perform(as(BOB_TOKEN, post(METHODS + "GOOGLE/turn-off"))).andExpect(status().isOk());

        assertThat(turnedOff(ANA)).isEmpty();
        assertThat(auditRows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("PERFORMED_BY", BOB_EMAIL)
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(BOB)));
    }

    @Test
    void anAccountWithoutAPasswordIsComparedAgainstAHashMadeByTheAppsOwnEncoder() {
        final var stored = userDetailsService.loadUserByUsername(BOB_EMAIL).getPassword();

        assertThat(StoredPasswords.isSet(stored)).isTrue();
        assertThat(stored.substring(0, 7)).isEqualTo(passwordEncoder.encode("any").substring(0, 7));
        assertThat(userDetailsService.loadUserByUsername(ANA_EMAIL).getPassword()).isEqualTo(PASSWORD_HASH);
    }

    private void account(final long id, final String email, final String password, final String authProvider) {
        jdbcTemplate.update("""
                insert into user_account (id, email, password, active, email_verified, auth_provider, two_step_enabled)
                values (?, ?, ?, true, true, ?, false)""", id, email, password, authProvider);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, ROLE_USER);
    }

    private void method(final long userId, final String method) {
        jdbcTemplate.update("insert into user_sign_in_method (user_id, method, first_used_at, last_used_at) values (?, ?, ?, ?)",
                userId, method, FIRST, LAST);
    }

    private void passkey(final long userId, final String credentialId, final Boolean discoverable) {
        jdbcTemplate.update("""
                insert into passkey_credential (user_id, credential_id, public_key, attestation_object, sign_count, uv_initialized,
                    backup_eligible, backup_state, discoverable, label, created_at)
                values (?, ?, 'key', 'attestation', 0, true, false, false, ?, 'Key', ?)""", userId, credentialId, discoverable, FIRST);
    }

    private List<String> turnedOff(final long userId) {
        return jdbcTemplate.queryForList("select method from user_sign_in_method where user_id = ? and turned_off_at is not null order by method",
                String.class, userId);
    }

    private LocalDateTime turnedOffAt(final long userId, final String method) {
        return jdbcTemplate.queryForObject("select turned_off_at from user_sign_in_method where user_id = ? and method = ?",
                LocalDateTime.class, userId, method);
    }

    private String passwordOf(final long userId) {
        return jdbcTemplate.queryForObject("select password from user_account where id = ?", String.class, userId);
    }

    private List<Map<String, Object>> auditRows() {
        return jdbcTemplate.queryForList("select performed_by, action, failed, target_type, target_identifier, details from audit_log order by id");
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList("select action from audit_log order by id", String.class);
    }

    private void signedIn(final String token, final String email) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void signedInJustNow(final String token, final String email) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, token, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(tokenProvider.issuedAt(token)).thenReturn(Optional.of(Instant.now()));
    }

    private static String reauthBody(final String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private static String passwordBody(final String password) {
        return "{\"currentPassword\":\"" + password + "\"}";
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
                    classes = {UserRepository.class, UserSignInMethodRepository.class, PasskeyCredentialRepository.class,
                            AuditLogRepository.class}))
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            ClockConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            AccountLock.class,
            RecentSignIn.class,
            DefaultAuthValidator.class,
            Reauthentication.class,
            DefaultAuditLogService.class,
            DefaultUserDetailsService.class,
            DefaultSignInMethodService.class,
            SignInMethodGuard.class,
            DefaultSignInMethodChangeService.class,
            SignInMethodChangedEmailListener.class,
            SignInMethodController.class
    })
    static class TestApplication {
    }

}
