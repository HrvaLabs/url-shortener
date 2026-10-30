package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyDeleteDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySecondStepDto;
import cc.hrva.urlshortener.dto.PendingTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.dto.TwoFactorCodeDto;
import cc.hrva.urlshortener.dto.TwoStepDisableDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.Totp;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.passkey.TestAuthenticator;
import com.nimbusds.jose.jwk.RSAKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.CLIENT_IP;
import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Transactional
@SpringBootTest(
        classes = PasskeyFlowsTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:passkey-second-step-flows;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.passkeys.rp-id=" + TestAuthenticator.RP_ID,
                "app.passkeys.allowed-origins=" + TestAuthenticator.ORIGIN,
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=" + PasskeySecondStepFlowsTest.JWT_SECRET,
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
class PasskeySecondStepFlowsTest {

    static final String JWT_SECRET =
            "cGFzc2tleS1zZWNvbmQtc3RlcC1mbG93cy10ZXN0LW9ubHktand0LXNlY3JldC14eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=";

    private static final String SIGN_IN_EXPIRED = "Your sign-in expired. Sign in again.";
    private static final String OUT_OF_ATTEMPTS = "Too many wrong codes. Sign in again to get 5 more tries.";
    private static final String EXPIRED = "This request expired. Try again.";
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String OTHER_ORIGIN = "https://evil.example";
    private static final String LAST_PASSKEY =
            "This is your only two-step method. Turn off two-step verification, or add another passkey, security key or authenticator app first.";
    private static final String PENDING_LOGIN_KEY_PREFIX = "auth:pending-login:";
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);
    private static final RSAKey SIGNING_KEY = PasskeyFlowsSupport.generateSigningKey();

    private final InMemoryRedis redis = new InMemoryRedis();
    private PasskeyFlowsSupport flows;

    @Autowired
    private SecondStepService secondStepService;

    @Autowired
    private SignInService signInService;

    @Autowired
    private PasskeyService passkeyService;

    @Autowired
    private TwoStepSettingsService twoStepSettingsService;

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private AnalyticsDays analyticsDays;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        redis.backing(redisTemplate);
        flows = new PasskeyFlowsSupport(userRepository, passwordEncoder, passkeyService, passkeyCredentialRepository,
                entityManager, jdbcTemplate, userService);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aNonDiscoverableSecurityKeyCompletesTheSecondStep(final CapturedOutput output) {
        final var user = flows.signedIn(flows.localUser("key-second-step@example.com"));
        try {
            final var authenticator = TestAuthenticator.securityKey();
            flows.addPasskey(authenticator, "YubiKey");
            flows.updated(user, account -> account.setTwoStepEnabled(true));
            assertThat(signCountOf(authenticator)).isEqualTo(1L);
            final var login = signInService.requireSecondStep(user, SignInMethod.PASSWORD, false).getBody();
            assertThat(login.getMethods()).containsExactly(TwoStepMethod.WEBAUTHN);

            final var options = passkeyOptions(login.getPendingToken());

            assertThat(options.options().path("allowCredentials").values())
                    .singleElement()
                    .satisfies(descriptor -> {
                        assertThat(descriptor.path("type").asString()).isEqualTo("public-key");
                        assertThat(descriptor.path("id").asString()).isEqualTo(authenticator.credentialId());
                        assertThat(descriptor.path("transports").values()).extracting(JsonNode::asString).containsExactly("usb", "nfc");
                    });
            assertThat(options.options().path("userVerification").asString()).isEqualTo("preferred");

            final var response = secondStepService.completeWithPasskey(
                    secondStep(login.getPendingToken(), options, authenticator.authenticate(options.options())), request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER)).startsWith("Bearer ");
            assertThat(response.getBody()).satisfies(body -> {
                assertThat(body.getToken()).isEqualTo(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER));
                assertThat(body.getUser().getId()).isEqualTo(user.getId());
                assertThat(body.getUser().getTwoStepEnabled()).isTrue();
                assertThat(body.getTwoStepRequired()).isNull();
                assertThat(body.getPendingToken()).isNull();
            });
            assertThat(passkeyCredentialRepository.findByCredentialId(authenticator.credentialId()).orElseThrow())
                    .satisfies(stored -> {
                        assertThat(stored.getSignCount()).isEqualTo(2L);
                        assertThat(stored.getLastUsedAt()).isNotNull();
                    });
            assertThat(redis.keys()).noneMatch(key -> key.startsWith(PENDING_LOGIN_KEY_PREFIX));
            verify(loginAttemptService).loginSucceeded("2fa:" + user.getId());
            verify(loginAttemptService, never()).loginFailed(anyString());
            assertThat(output)
                    .contains("Second step completed user id=" + user.getId())
                    .doesNotContain(login.getPendingToken())
                    .doesNotContain(options.ceremonyId())
                    .doesNotContain(options.options().path("challenge").asString())
                    .doesNotContain(authenticator.credentialId());
        } finally {
            flows.remove(user);
        }
    }

    @Test
    void aSecondStepWithoutUserVerificationIsAccepted() {
        final var user = flows.signedIn(flows.localUser("no-uv-second-step@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");

        final var response = completeWith(authenticator.withoutUserVerification(), pendingTokenOf(user, false));

        assertThat(response.getBody().getUser().getId()).isEqualTo(user.getId());
        assertThat(flows.stored(authenticator).getLastUsedAt()).isNotNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anotherAccountsPasskeyCantCompleteTheSecondStep(final CapturedOutput output) {
        final var alice = flows.signedIn(flows.localUser("alice-second-step@example.com"));
        final var alicesKey = TestAuthenticator.securityKey();
        flows.addPasskey(alicesKey, "YubiKey");
        flows.signedIn(flows.localUser("bob-second-step@example.com"));
        final var bobsPasskey = TestAuthenticator.syncedPasskey();
        flows.addPasskey(bobsPasskey, "MacBook");
        final var alicesToken = pendingTokenOf(alice, false);
        final var options = passkeyOptions(alicesToken);
        final var bobsCredential = bobsPasskey.authenticate(options.options());

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(secondStep(alicesToken, options, bobsCredential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);

        verify(loginAttemptService).loginFailed("2fa:" + alice.getId());
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService, never()).loginSucceeded(startsWith("2fa:"));
        verify(userService, never()).userHasLoggedIn(any());
        assertThat(flows.stored(bobsPasskey).getLastUsedAt()).isNull();
        assertThat(output)
                .contains("Passkey of another account user id=" + alice.getId() + " credential="
                        + bobsPasskey.credentialId().substring(0, 8) + "… purpose=SECOND_STEP")
                .doesNotContain(bobsPasskey.credentialId())
                .doesNotContain(alicesToken)
                .doesNotContain(options.ceremonyId())
                .doesNotContain(options.options().path("challenge").asString());
        assertThat(completeWith(alicesKey, alicesToken).getBody().getUser().getId()).isEqualTo(alice.getId());
    }

    @Test
    void theCeremonyOfOnePendingLoginCantFinishAnother() {
        final var alice = flows.signedIn(flows.localUser("alice-ceremony@example.com"));
        final var alicesKey = TestAuthenticator.securityKey();
        flows.addPasskey(alicesKey, "YubiKey");
        final var bob = flows.signedIn(flows.localUser("bob-ceremony@example.com"));
        flows.addPasskey(TestAuthenticator.securityKey(), "YubiKey");
        final var alicesOptions = passkeyOptions(pendingTokenOf(alice, false));
        final var bobsToken = pendingTokenOf(bob, false);
        final var alicesCredential = alicesKey.authenticate(alicesOptions.options());

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(secondStep(bobsToken, alicesOptions, alicesCredential), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);

        assertThat(flows.stored(alicesKey).getLastUsedAt()).isNull();
        verify(loginAttemptService).loginFailed(CLIENT_IP);
        verify(loginAttemptService, never()).loginFailed("2fa:" + bob.getId());
        verify(loginAttemptService, never()).loginSucceeded(startsWith("2fa:"));
        verify(userService, never()).userHasLoggedIn(any());
    }

    @Test
    void theSecondStepKeepsRememberMe() {
        final var user = flows.signedIn(flows.localUser("remembered-second-step@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();
        flows.addPasskey(authenticator, "Pixel");

        final var remembered = claimsOf(completeWith(authenticator, pendingTokenOf(user, true)));
        final var notRemembered = claimsOf(completeWith(authenticator, pendingTokenOf(user, false)));

        assertThat(lifetimeOf(remembered)).isEqualTo(THIRTY_DAYS);
        assertThat(remembered.getExpiration().toInstant()).isCloseTo(Instant.now().plus(THIRTY_DAYS), within(1, ChronoUnit.MINUTES));
        assertThat(lifetimeOf(notRemembered)).isEqualTo(Duration.ofSeconds(appProperties.getJwtTokenValiditySeconds()));
    }

    @Test
    void theFifthFailedPasskeyAttemptEndsThePendingLogin() {
        final var user = flows.signedIn(flows.localUser("five-attempts@example.com"));
        final var authenticator = TestAuthenticator.securityKey();
        flows.addPasskey(authenticator, "YubiKey");
        final var pendingToken = pendingTokenOf(user, false);

        for (final var message : List.of(COULD_NOT_VERIFY, COULD_NOT_VERIFY, COULD_NOT_VERIFY + " 2 tries left.",
                COULD_NOT_VERIFY + " 1 try left.")) {
            assertThatThrownBy(() -> completeWith(authenticator.withOrigin(OTHER_ORIGIN), pendingToken))
                    .isInstanceOf(PasskeyVerificationException.class)
                    .hasMessage(message);
        }
        final var options = passkeyOptions(pendingToken);
        final var credential = authenticator.withOrigin(OTHER_ORIGIN).authenticate(options.options());

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(secondStep(pendingToken, options, credential), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(OUT_OF_ATTEMPTS);

        assertThat(redis.keys()).doesNotContain(PENDING_LOGIN_KEY_PREFIX + pendingToken);
        assertThat(flows.stored(authenticator).getLastUsedAt()).isNull();
        verify(loginAttemptService, times(5)).loginFailed("2fa:" + user.getId());
        verify(loginAttemptService, times(5)).loginFailed(CLIENT_IP);
        assertThatThrownBy(() -> passkeyOptions(pendingToken))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
    }

    @Test
    void wrongCodesAndFailedPasskeysShareTheFiveAttempts() {
        final var user = flows.signedIn(flows.localUser("shared-attempts@example.com"));
        final var authenticator = TestAuthenticator.securityKey();
        flows.addPasskey(authenticator, "YubiKey");
        final var pendingToken = pendingTokenOf(user, false);
        final var wrongCode = TotpSecondStepDto.builder().pendingToken(pendingToken).code("000000").build();

        for (final var message : List.of("Invalid two-factor code", "Invalid two-factor code", "Invalid two-factor code. 2 tries left.")) {
            assertThatThrownBy(() -> secondStepService.completeWithCode(wrongCode, request()))
                    .isInstanceOf(TwoStepFailedException.class)
                    .hasMessage(message);
        }
        assertThatThrownBy(() -> completeWith(authenticator.withOrigin(OTHER_ORIGIN), pendingToken))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY + " 1 try left.");
        assertThatThrownBy(() -> completeWith(authenticator.withOrigin(OTHER_ORIGIN), pendingToken))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(OUT_OF_ATTEMPTS);

        assertThatThrownBy(() -> completeWith(authenticator, pendingToken))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(loginAttemptService, times(5)).loginFailed("2fa:" + user.getId());
    }

    @Test
    void codesTypedTheWayPeopleSeeThemWorkOnEveryCodePath() {
        final var user = flows.signedIn(flows.localUser("typed-codes@example.com"));
        flows.addPasskey(TestAuthenticator.securityKey(), "YubiKey");
        final var secret = twoFactorService.setup().secret();
        final var recoveryCodes = twoFactorService.enable(" " + spaced(Totp.currentCode(secret)) + " ").recoveryCodes();
        assertThat(recoveryCodes).hasSize(10);
        flows.signedInAgain(user);

        for (final var typed : List.of(
                recoveryCodes.get(0).toLowerCase(Locale.ROOT),
                " " + recoveryCodes.get(1) + " ",
                recoveryCodes.get(2) + "\n",
                recoveryCodes.get(3).replace("-", ""),
                recoveryCodes.get(4).replace("-", " "),
                spaced(Totp.currentCode(secret)),
                Totp.currentCode(secret) + " ")) {
            final var signedIn = secondStepService.completeWithCode(code(pendingTokenOf(user, false), typed), request());

            assertThat(signedIn.getStatusCode()).as(typed).isEqualTo(HttpStatus.OK);
            assertThat(signedIn.getBody().getToken()).as(typed).isNotBlank();
        }
        assertThat(unusedRecoveryCodesOf(user)).isEqualTo(5L);

        final var usedCode = code(pendingTokenOf(user, false), recoveryCodes.get(0).toLowerCase(Locale.ROOT));
        assertThatThrownBy(() -> secondStepService.completeWithCode(usedCode, request()))
                .isInstanceOf(TwoStepFailedException.class)
                .hasMessage("Invalid two-factor code");
        verify(loginAttemptService).loginFailed("2fa:" + user.getId());

        twoStepSettingsService.removeTotp(new TwoFactorCodeDto("\t" + spaced(Totp.currentCode(secret)) + "\n"));
        flushed();
        assertThat(jdbcTemplate.queryForObject("select two_factor_enabled from user_account where id = ?", Boolean.class, user.getId()))
                .isFalse();

        twoStepSettingsService.disable(TwoStepDisableDto.builder()
                .code(recoveryCodes.get(5).toLowerCase(Locale.ROOT).replace("-", ""))
                .build());
        assertThat(twoStepEnabledOf(user)).isFalse();
        verify(loginAttemptService, times(1)).loginFailed("2fa:" + user.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCodeSecondStepAnswerIsWrittenAfterItsTransactionWithTheRoles() {
        final var user = flows.signedIn(flows.withTheUserRole(flows.localUser("code-answer@example.com")));
        try {
            final var secret = twoFactorService.setup().secret();
            final var recoveryCodes = twoFactorService.enable(Totp.currentCode(secret)).recoveryCodes();
            flows.signedInAgain(user);

            final var response = secondStepService.completeWithCode(code(pendingTokenOf(user, false), recoveryCodes.getFirst()), request());

            assertThat(PasskeyFlowsSupport.rolesWrittenIn(response)).containsExactly("ROLE_USER");
            assertThat(flows.signInMethodsOf(user)).containsExactly("PASSWORD");
        } finally {
            flows.remove(user);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aPasskeySecondStepAnswerIsWrittenAfterItsTransactionWithTheRoles() {
        final var user = flows.signedIn(flows.withTheUserRole(flows.localUser("passkey-second-step-answer@example.com")));
        try {
            final var authenticator = TestAuthenticator.securityKey();
            flows.addPasskey(authenticator, "YubiKey");

            final var response = completeWith(authenticator, pendingTokenOf(user, false));

            assertThat(PasskeyFlowsSupport.rolesWrittenIn(response)).containsExactly("ROLE_USER");
        } finally {
            flows.remove(user);
        }
    }

    @Test
    void aPasskeySecondStepCountsAsAPasskeySignIn() {
        final var user = flows.signedIn(flows.localUser("counted-second-step@example.com"));
        final var authenticator = TestAuthenticator.securityKey();
        flows.addPasskey(authenticator, "YubiKey");
        final var today = analyticsDays.today();
        final var before = flows.signInsOn(today);

        completeWith(authenticator, pendingTokenOf(user, false));
        assertThat(flows.signInsOn(today)).isEqualTo(before + 1);

        assertThatThrownBy(() -> completeWith(authenticator.withOrigin(OTHER_ORIGIN), pendingTokenOf(user, false)))
                .isInstanceOf(PasskeyVerificationException.class);
        assertThat(flows.signInsOn(today)).isEqualTo(before + 1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void turningOffWithAPasskeyAssertion(final CapturedOutput output) {
        final var user = flows.signedIn(flows.localUser("turn-off-with-key@example.com"));
        try {
            final var authenticator = TestAuthenticator.securityKey();
            flows.addPasskey(authenticator, "YubiKey");
            final var recoveryCodes = twoStepSettingsService.enableWithPasskeys().recoveryCodes();
            assertThat(recoveryCodes).hasSize(10);
            assertThat(twoStepEnabledOf(user)).isTrue();
            assertThat(recoveryCodesOf(user)).isEqualTo(10L);
            flows.signedInAgain(user);

            final var options = twoStepSettingsService.disableOptions();

            assertThat(options.options().path("allowCredentials").values())
                    .singleElement()
                    .satisfies(descriptor -> {
                        assertThat(descriptor.path("id").asString()).isEqualTo(authenticator.credentialId());
                        assertThat(descriptor.path("transports").values()).extracting(JsonNode::asString).containsExactly("usb", "nfc");
                    });
            assertThat(options.options().path("userVerification").asString()).isEqualTo("preferred");

            twoStepSettingsService.disable(turnOff(options, authenticator.authenticate(options.options())));

            assertThat(twoStepEnabledOf(user)).isFalse();
            assertThat(jdbcTemplate.queryForObject("select two_factor_enabled from user_account where id = ?", Boolean.class, user.getId()))
                    .isFalse();
            assertThat(recoveryCodesOf(user)).isZero();
            assertThat(passkeyCredentialRepository.findByCredentialId(authenticator.credentialId()).orElseThrow())
                    .satisfies(stored -> {
                        assertThat(stored.getUser().getId()).isEqualTo(user.getId());
                        assertThat(stored.getSignCount()).isEqualTo(2L);
                        assertThat(stored.getLastUsedAt()).isNotNull();
                    });
            verify(loginAttemptService).loginSucceeded("2fa:" + user.getId());
            verify(loginAttemptService, never()).loginFailed(anyString());
            assertThat(output)
                    .contains("Two-step verification turned off user id=" + user.getId())
                    .doesNotContain(options.ceremonyId())
                    .doesNotContain(options.options().path("challenge").asString())
                    .doesNotContain(authenticator.credentialId());
            assertThat(recoveryCodes).allSatisfy(code -> assertThat(output).doesNotContain(code));
        } finally {
            flows.remove(user);
        }
    }

    @Test
    void aTurnOffCeremonyOfAnotherAccountIsRefused() {
        final var alice = flows.signedIn(flows.localUser("alice-turn-off@example.com"));
        flows.addPasskey(TestAuthenticator.securityKey(), "YubiKey");
        twoStepSettingsService.enableWithPasskeys();
        final var bob = flows.signedIn(flows.localUser("bob-turn-off@example.com"));
        final var bobsKey = TestAuthenticator.securityKey();
        flows.addPasskey(bobsKey, "YubiKey");
        twoStepSettingsService.enableWithPasskeys();
        flows.signedInAgain(bob);
        final var bobsOptions = twoStepSettingsService.disableOptions();
        final var bobsCredential = bobsKey.authenticate(bobsOptions.options());
        flows.signedInAgain(alice);

        assertThatThrownBy(() -> twoStepSettingsService.disable(turnOff(bobsOptions, bobsCredential)))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);

        assertThat(twoStepEnabledOf(alice)).isTrue();
        assertThat(recoveryCodesOf(alice)).isEqualTo(10L);
        assertThat(flows.stored(bobsKey).getLastUsedAt()).isNull();
        verify(loginAttemptService, never()).loginFailed(anyString());
        verify(loginAttemptService, never()).loginSucceeded(startsWith("2fa:"));
    }

    @Test
    void anotherAccountsPasskeyCantTurnTwoStepOff() {
        final var alice = flows.signedIn(flows.localUser("alice-other-key@example.com"));
        flows.addPasskey(TestAuthenticator.securityKey(), "YubiKey");
        twoStepSettingsService.enableWithPasskeys();
        flows.signedIn(flows.localUser("bob-other-key@example.com"));
        final var bobsPasskey = TestAuthenticator.syncedPasskey();
        flows.addPasskey(bobsPasskey, "MacBook");
        flows.signedInAgain(alice);
        final var options = twoStepSettingsService.disableOptions();
        final var bobsCredential = bobsPasskey.authenticate(options.options());

        assertThatThrownBy(() -> twoStepSettingsService.disable(turnOff(options, bobsCredential)))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);

        assertThat(twoStepEnabledOf(alice)).isTrue();
        assertThat(recoveryCodesOf(alice)).isEqualTo(10L);
        assertThat(flows.stored(bobsPasskey).getLastUsedAt()).isNull();
        verify(loginAttemptService).loginFailed("2fa:" + alice.getId());
        verify(loginAttemptService, never()).loginSucceeded(startsWith("2fa:"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void removingBothPasskeysOfAKeysOnlyAccountAtOnceLeavesOne() throws Exception {
        final var user = flows.signedIn(flows.localUser("two-keys-at-once@example.com"));
        try {
            final var first = flows.addPasskey(TestAuthenticator.securityKey(), "YubiKey");
            final var second = flows.addPasskey(TestAuthenticator.syncedPasskey(), "MacBook");
            twoStepSettingsService.enableWithPasskeys();
            flows.signedInAgain(user);
            final var firstRemoved = new CountDownLatch(1);
            final var secondFinished = new CountDownLatch(1);
            final var executor = Executors.newSingleThreadExecutor();
            try {
                final Future<String> secondRemoval = executor.submit(() -> {
                    try {
                        firstRemoved.await(10, TimeUnit.SECONDS);
                        passkeyService.delete(second.id(), new PasskeyDeleteDto(PasskeyFlowsSupport.PASSWORD));
                        return "removed";
                    } catch (final ApiException | PessimisticLockingFailureException refused) {
                        return refused.getMessage();
                    } finally {
                        secondFinished.countDown();
                    }
                });
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    passkeyService.delete(first.id(), new PasskeyDeleteDto(PasskeyFlowsSupport.PASSWORD));
                    firstRemoved.countDown();
                    waitAtMostASecond(secondFinished);
                });

                assertThat(secondRemoval.get(10, TimeUnit.SECONDS)).isEqualTo(LAST_PASSKEY);
            } finally {
                executor.shutdownNow();
            }

            assertThat(jdbcTemplate.queryForList("select id from passkey_credential where user_id = ?", Long.class, user.getId()))
                    .containsExactly(second.id());
            assertThat(twoStepEnabledOf(user)).isTrue();
        } finally {
            flows.remove(user);
        }
    }

    private String pendingTokenOf(final User user, final boolean rememberMe) {
        return signInService.requireSecondStep(user, SignInMethod.PASSWORD, rememberMe).getBody().getPendingToken();
    }

    private PasskeyOptionsResponse passkeyOptions(final String pendingToken) {
        return secondStepService.passkeyOptions(new PendingTokenDto(pendingToken), request());
    }

    private ResponseEntity<JWTTokenDto> completeWith(final TestAuthenticator authenticator, final String pendingToken) {
        final var options = passkeyOptions(pendingToken);

        return secondStepService.completeWithPasskey(secondStep(pendingToken, options, authenticator.authenticate(options.options())), request());
    }

    private long signCountOf(final TestAuthenticator authenticator) {
        return passkeyCredentialRepository.findByCredentialId(authenticator.credentialId()).orElseThrow().getSignCount();
    }

    private long unusedRecoveryCodesOf(final User user) {
        flushed();

        return jdbcTemplate.queryForObject("select count(*) from two_factor_recovery_code where user_id = ? and used = false", Long.class,
                user.getId());
    }

    private static TotpSecondStepDto code(final String pendingToken, final String code) {
        return TotpSecondStepDto.builder().pendingToken(pendingToken).code(code).build();
    }

    private static String spaced(final String totpCode) {
        return totpCode.substring(0, 3) + " " + totpCode.substring(3);
    }

    private boolean twoStepEnabledOf(final User user) {
        flushed();

        return jdbcTemplate.queryForObject("select two_step_enabled from user_account where id = ?", Boolean.class, user.getId());
    }

    private long recoveryCodesOf(final User user) {
        flushed();

        return jdbcTemplate.queryForObject("select count(*) from two_factor_recovery_code where user_id = ?", Long.class, user.getId());
    }

    private void flushed() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.flush();
        }
    }

    private static void waitAtMostASecond(final CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static TwoStepDisableDto turnOff(final PasskeyOptionsResponse options, final JsonNode credential) {
        return TwoStepDisableDto.builder()
                .ceremonyId(options.ceremonyId())
                .credential(credential)
                .build();
    }

    private static PasskeySecondStepDto secondStep(final String pendingToken, final PasskeyOptionsResponse options, final JsonNode credential) {
        return PasskeySecondStepDto.builder()
                .pendingToken(pendingToken)
                .ceremonyId(options.ceremonyId())
                .credential(credential)
                .build();
    }

    private static Claims claimsOf(final ResponseEntity<JWTTokenDto> response) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET)))
                .build()
                .parseSignedClaims(StringUtils.removeStart(response.getBody().getToken(), "Bearer "))
                .getPayload();
    }

    private static Duration lifetimeOf(final Claims claims) {
        return Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
    }

}
