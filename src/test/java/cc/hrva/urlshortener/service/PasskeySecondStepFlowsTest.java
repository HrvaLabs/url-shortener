package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySecondStepDto;
import cc.hrva.urlshortener.dto.PendingTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.JwtFilter;
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
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.CLIENT_IP;
import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private static final String EXPIRED = "This request expired. Try again.";
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String OTHER_ORIGIN = "https://evil.example";
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
            final var login = signInService.requireSecondStep(user, false).getBody();
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
        verify(loginAttemptService, never()).loginSucceeded(anyString());
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
        verify(loginAttemptService, never()).loginSucceeded(anyString());
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
    void theSixthFailedPasskeyAttemptEndsThePendingLogin() {
        final var user = flows.signedIn(flows.localUser("six-attempts@example.com"));
        final var authenticator = TestAuthenticator.securityKey();
        flows.addPasskey(authenticator, "YubiKey");
        final var pendingToken = pendingTokenOf(user, false);

        IntStream.rangeClosed(1, 5).forEach(attempt ->
                assertThatThrownBy(() -> completeWith(authenticator.withOrigin(OTHER_ORIGIN), pendingToken))
                        .isInstanceOf(PasskeyVerificationException.class)
                        .hasMessage(COULD_NOT_VERIFY));
        final var options = passkeyOptions(pendingToken);
        final var credential = authenticator.authenticate(options.options());

        assertThatThrownBy(() -> secondStepService.completeWithPasskey(secondStep(pendingToken, options, credential), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);

        assertThat(redis.keys()).doesNotContain(PENDING_LOGIN_KEY_PREFIX + pendingToken);
        assertThat(flows.stored(authenticator).getLastUsedAt()).isNull();
        verify(loginAttemptService, times(5)).loginFailed("2fa:" + user.getId());
        verify(loginAttemptService, times(6)).loginFailed(CLIENT_IP);
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

        IntStream.rangeClosed(1, 3).forEach(attempt ->
                assertThatThrownBy(() -> secondStepService.completeWithCode(wrongCode, request()))
                        .isInstanceOf(TwoStepFailedException.class));
        IntStream.rangeClosed(1, 2).forEach(attempt ->
                assertThatThrownBy(() -> completeWith(authenticator.withOrigin(OTHER_ORIGIN), pendingToken))
                        .isInstanceOf(PasskeyVerificationException.class));

        assertThatThrownBy(() -> completeWith(authenticator, pendingToken))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(SIGN_IN_EXPIRED);
        verify(loginAttemptService, times(5)).loginFailed("2fa:" + user.getId());
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

    private String pendingTokenOf(final User user, final boolean rememberMe) {
        return signInService.requireSecondStep(user, rememberMe).getBody().getPendingToken();
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
