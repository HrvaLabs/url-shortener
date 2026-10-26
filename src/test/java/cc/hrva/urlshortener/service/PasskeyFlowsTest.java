package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.PasskeyConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.dto.PasskeySignInDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.PasskeySignInStatsRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.RecentSignIn;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultPasskeyService;
import cc.hrva.urlshortener.service.impl.DefaultPasskeySignInService;
import cc.hrva.urlshortener.service.impl.DefaultSignInService;
import cc.hrva.urlshortener.service.passkey.JpaPublicKeyCredentialUserEntityRepository;
import cc.hrva.urlshortener.service.passkey.JpaUserCredentialRepository;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.passkey.RedisPasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.TestAuthenticator;
import cc.hrva.urlshortener.service.twostep.RedisPendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import cc.hrva.urlshortener.validator.impl.DefaultAuthValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Transactional
@SpringBootTest(
        classes = PasskeyFlowsTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:passkey-flows;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.passkeys.rp-id=" + TestAuthenticator.RP_ID,
                "app.passkeys.allowed-origins=" + TestAuthenticator.ORIGIN,
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=cGFzc2tleS1mbG93cy10ZXN0LW9ubHktand0LXNlY3JldC14eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
class PasskeyFlowsTest {

    private static final String PASSWORD = "user123";
    private static final String EXPIRED = "This request expired. Try again.";
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String NOT_REGISTERED =
            "This passkey isn't registered on hrva.cc. Remove it from your password manager or device, or sign in another way.";
    private static final String CLIENT_IP = "203.0.113.9";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    private final InMemoryRedis redis = new InMemoryRedis();

    @Autowired
    private PasskeyService passkeyService;

    @Autowired
    private PasskeySignInService passkeySignInService;

    @Autowired
    private PasskeyAssertions passkeyAssertions;

    @Autowired
    private AnalyticsDays analyticsDays;

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
    private RateLimiter rateLimiter;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        redis.backing(redisTemplate);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void registersASyncedPasskey() {
        final var user = signedIn(localUser("synced@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();

        final var passkey = addPasskey(authenticator, "MacBook");

        assertThat(passkey.id()).isNotNull();
        assertThat(passkey.label()).isEqualTo("MacBook");
        assertThat(passkey.kind()).isEqualTo(PasskeyKind.SYNCED);
        assertThat(passkey.backupEligible()).isTrue();
        assertThat(passkey.backedUp()).isTrue();
        assertThat(passkey.discoverable()).isTrue();
        assertThat(passkey.transports()).containsExactly("hybrid", "internal");
        assertThat(passkey.createdAt()).isNotNull();
        assertThat(passkey.lastUsedAt()).isNull();
        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getUser().getId()).isEqualTo(user.getId());
            assertThat(stored.getTransports()).isEqualTo("hybrid,internal");
            assertThat(stored.getDiscoverable()).isTrue();
            assertThat(stored.getSignCount()).isZero();
            assertThat(stored.getAaguid()).isNull();
            assertThat(stored.getLastUsedAt()).isNull();
        });
        assertThat(passkeyService.list()).extracting(PasskeyResponse::id).containsExactly(passkey.id());
    }

    @Test
    void registersADeviceBoundPasskey() {
        signedIn(localUser("device@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();

        final var passkey = addPasskey(authenticator, "Pixel");

        assertThat(passkey.kind()).isEqualTo(PasskeyKind.DEVICE_BOUND);
        assertThat(passkey.backupEligible()).isFalse();
        assertThat(passkey.discoverable()).isTrue();
        assertThat(passkey.transports()).containsExactly("internal");
        assertThat(stored(authenticator).getSignCount()).isEqualTo(1L);
    }

    @Test
    void registersANonDiscoverableSecurityKeyAsSecondStepOnly() {
        signedIn(localUser("key@example.com"));
        final var authenticator = TestAuthenticator.securityKey();

        final var passkey = addPasskey(authenticator, "YubiKey");

        assertThat(passkey.kind()).isEqualTo(PasskeyKind.SECOND_STEP_ONLY);
        assertThat(passkey.discoverable()).isFalse();
        assertThat(passkey.backupEligible()).isFalse();
        assertThat(passkey.backedUp()).isFalse();
        assertThat(passkey.transports()).containsExactly("usb", "nfc");
        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getTransports()).isEqualTo("usb,nfc");
            assertThat(stored.isUvInitialized()).isFalse();
            assertThat(stored.getSignCount()).isEqualTo(1L);
        });
    }

    @Test
    void aCredentialWithoutTransportsRegisters() {
        signedIn(localUser("no-transports@example.com"));
        final var authenticator = TestAuthenticator.securityKey().withoutTransports();

        final var passkey = addPasskey(authenticator, "Old key");

        assertThat(passkey.transports()).isEmpty();
        assertThat(stored(authenticator).getTransports()).isNull();
        assertThat(registrationOptions().options().path("excludeCredentials").values())
                .singleElement()
                .satisfies(descriptor -> {
                    assertThat(descriptor.path("id").asString()).isEqualTo(authenticator.credentialId());
                    assertThat(descriptor.path("transports").values()).isEmpty();
                });
    }

    @Test
    void anUnknownTransportIsDroppedAndTheKnownOneStored() {
        signedIn(localUser("cable@example.com"));
        final var authenticator = TestAuthenticator.securityKey().withTransports("cable", "usb");

        final var passkey = addPasskey(authenticator, "Phone");

        assertThat(passkey.transports()).containsExactly("usb");
        assertThat(stored(authenticator).getTransports()).isEqualTo("usb");
    }

    @Test
    void anAuthenticatorWithoutUserPresenceIsRefused() {
        signedIn(localUser("presence@example.com"));
        final var options = registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().withoutUserPresence().register(options.options());

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aCeremonyCanBeUsedOnce() {
        signedIn(localUser("once@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        final var options = registrationOptions();
        final var credential = authenticator.register(options.options());
        passkeyService.register(registration(options, credential, "MacBook"));

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        assertThat(passkeyCredentialRepository.count()).isEqualTo(1L);
    }

    @Test
    void anotherUsersCeremonyIsRefused() {
        signedIn(localUser("alice@example.com"));
        final var alicesOptions = registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().register(alicesOptions.options());
        signedIn(localUser("bob@example.com"));

        assertThatThrownBy(() -> passkeyService.register(registration(alicesOptions, credential, "Not mine")))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aWrongOriginIsRefused() {
        signedIn(localUser("origin@example.com"));
        final var options = registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().withOrigin("https://evil.example").register(options.options());

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aResponseToAnotherChallengeIsRefused() {
        signedIn(localUser("challenge@example.com"));
        final var answered = registrationOptions();
        final var sent = registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().register(answered.options());

        assertThatThrownBy(() -> passkeyService.register(registration(sent, credential, "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aCredentialForAnotherSiteIsRefused() {
        signedIn(localUser("site@example.com"));
        final var options = registrationOptions();
        final var otherSite = options.options().deepCopy();
        ((ObjectNode) otherSite.get("rp")).put("id", "evil.example");

        assertThatThrownBy(() -> passkeyService.register(registration(options, TestAuthenticator.syncedPasskey().register(otherSite), "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void theSameAuthenticatorCantBeAddedTwice() {
        signedIn(localUser("twice@example.com"));
        final var authenticator = TestAuthenticator.securityKey();
        addPasskey(authenticator, "YubiKey");
        final var options = registrationOptions();
        assertThat(options.options().path("excludeCredentials").values())
                .extracting(descriptor -> descriptor.path("id").asString())
                .containsExactly(authenticator.credentialId());

        assertThatThrownBy(() -> passkeyService.register(registration(options, authenticator.register(options.options()), "Again")))
                .isInstanceOf(ApiException.class)
                .hasMessage("This passkey or security key is already added.");
        assertThat(passkeyCredentialRepository.count()).isEqualTo(1L);
    }

    @Test
    void aLabelOver64CharactersIs400() {
        signedIn(localUser("label@example.com"));
        final var options = registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().register(options.options());

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "x".repeat(65))))
                .isInstanceOf(ApiException.class)
                .hasMessage("A label can have at most 64 characters.");
        assertThat(passkeyCredentialRepository.count()).isZero();

        final var passkey = passkeyService.register(registration(options, credential, "  " + "x".repeat(64) + "  "));
        assertThat(passkey.label()).isEqualTo("x".repeat(64));
    }

    @Test
    void aBlankLabelBecomesPasskey() {
        signedIn(localUser("blank@example.com"));

        assertThat(addPasskey(TestAuthenticator.syncedPasskey(), "   ").label()).isEqualTo("Passkey");
        assertThat(addPasskey(TestAuthenticator.securityKey(), null).label()).isEqualTo("Passkey");
    }

    @Test
    void theUserHandleIsCreatedOnceAndKept() {
        final var user = signedIn(localUser("handle@example.com"));

        final var first = registrationOptions().options().path("user").path("id").asString();
        final var second = registrationOptions().options().path("user").path("id").asString();

        assertThat(first).hasSize(43).isEqualTo(second);
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getWebauthnUserHandle()).isEqualTo(first);
    }

    @Test
    void twoFirstRequestsRacingGetTheSameHandle() {
        final var user = localUser("race@example.com");
        when(userService.getUserFromToken()).thenReturn(requestsViewOf(user), requestsViewOf(user));

        final var first = registrationOptions().options().path("user").path("id").asString();
        final var second = registrationOptions().options().path("user").path("id").asString();

        assertThat(first).hasSize(43);
        assertThat(second).isEqualTo(first);
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getWebauthnUserHandle()).isEqualTo(first);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDiscoverablePasskeySignsInWithoutAPassword() {
        final var user = signedIn(localUser("passwordless@example.com"));
        try {
            final var authenticator = TestAuthenticator.deviceBoundPasskey();
            addPasskey(authenticator, "Pixel");

            final var response = signInWith(authenticator);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER)).startsWith("Bearer ");
            assertThat(response.getBody()).satisfies(body -> {
                assertThat(body.getToken()).isEqualTo(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER));
                assertThat(body.getUser().getId()).isEqualTo(user.getId());
                assertThat(body.getUser().getEmail()).isEqualTo("passwordless@example.com");
                assertThat(body.getTwoStepRequired()).isNull();
                assertThat(body.getPendingToken()).isNull();
            });
            assertThat(passkeyCredentialRepository.findByCredentialId(authenticator.credentialId()).orElseThrow())
                    .satisfies(stored -> {
                        assertThat(stored.getSignCount()).isEqualTo(2L);
                        assertThat(stored.getLastUsedAt()).isNotNull();
                    });
            verify(loginAttemptService).loginSucceeded(CLIENT_IP);
        } finally {
            passkeyCredentialRepository.deleteAll(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user));
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAssertionOutsideATransactionIsRefusedBeforeItIsRead() {
        final var ceremony = new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, Bytes.random().toBase64UrlString(), null);
        final var unreadable = JsonMapper.builder().build().createObjectNode();

        assertThatThrownBy(() -> passkeyAssertions.verify(ceremony, unreadable, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aSyncedPasskeyReportingZeroTwiceStillSignsIn() {
        final var user = signedIn(localUser("zero@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");

        assertThat(signInWith(authenticator).getBody().getUser().getId()).isEqualTo(user.getId());
        assertThat(signInWith(authenticator).getBody().getUser().getId()).isEqualTo(user.getId());

        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getSignCount()).isZero();
            assertThat(stored.getLastUsedAt()).isNotNull();
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCounterThatDidNotIncreaseIsRefusedAndLoggedWithoutTheFullCredentialId(final CapturedOutput output) {
        final var user = signedIn(localUser("cloned@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();
        addPasskey(authenticator, "Pixel");
        final var credentialId = authenticator.credentialId();

        assertThatThrownBy(() -> signInWith(authenticator.withNextCounter(1)))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);

        assertThat(output)
                .contains("Passkey sign count did not increase user id=" + user.getId() + " credential="
                        + credentialId.substring(0, 8) + "… stored=1 presented=1")
                .doesNotContain(credentialId);
        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getSignCount()).isEqualTo(1L);
            assertThat(stored.getLastUsedAt()).isNull();
        });
        verify(loginAttemptService).loginFailed(CLIENT_IP);
    }

    @Test
    void aCounterDroppingToZeroIsAccepted() {
        signedIn(localUser("reset@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey().withNextCounter(5);
        addPasskey(authenticator, "Pixel");

        assertThat(signInWith(authenticator.withNextCounter(0)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getSignCount()).isEqualTo(5L);
            assertThat(stored.getLastUsedAt()).isNotNull();
        });
    }

    @Test
    void aPasskeyWithoutUserVerificationIsRefusedForPasswordlessSignIn() {
        signedIn(localUser("no-uv@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");

        assertThatThrownBy(() -> signInWith(authenticator.withoutUserVerification()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(stored(authenticator).getLastUsedAt()).isNull();
    }

    @Test
    void aPasskeyDeletedBetweenOptionsAndVerifyIsA401NotA500() {
        signedIn(localUser("deleted@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        final var credential = authenticator.authenticate(options.options());
        passkeyCredentialRepository.delete(stored(authenticator));
        entityManager.flush();

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(NOT_REGISTERED);
    }

    @Test
    void aReplayedCeremonyIsRefused() {
        signedIn(localUser("replay@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        passkeySignInService.verify(signIn(options, authenticator.authenticate(options.options())), request());

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, authenticator.authenticate(options.options())), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
    }

    @Test
    void aRegistrationCeremonyCantBeUsedToSignIn() {
        signedIn(localUser("wrong-ceremony@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        final var registration = registrationOptions();
        final var requestShaped = JsonMapper.builder().build().createObjectNode()
                .put("challenge", registration.options().path("challenge").asString())
                .put("rpId", TestAuthenticator.RP_ID);

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(registration, authenticator.authenticate(requestShaped)), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        assertThat(stored(authenticator).getLastUsedAt()).isNull();
    }

    @Test
    void aMismatchedUserHandleIsRefused() {
        signedIn(localUser("alice-handle@example.com"));
        final var alicesPasskey = TestAuthenticator.syncedPasskey();
        addPasskey(alicesPasskey, "Alice");
        final var bob = signedIn(localUser("bob-handle@example.com"));
        final var bobsHandle = registrationOptions().options().path("user").path("id").asString();
        assertThat(userRepository.findWebauthnUserHandleById(bob.getId())).contains(bobsHandle);
        final var options = passkeySignInService.options(request());
        final var credential = alicesPasskey.authenticate(options.options());
        ((ObjectNode) credential.get("response")).put("userHandle", bobsHandle);

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
    }

    @Test
    void aMissingUserHandleIsRefusedForPasswordlessSignIn() {
        signedIn(localUser("no-handle@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        final var credential = authenticator.authenticate(options.options());
        ((ObjectNode) credential.get("response")).remove("userHandle");

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
    }

    @Test
    void aTruncatedAuthenticatorDataIsA401NotA500() {
        signedIn(localUser("truncated@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        final var credential = authenticator.authenticate(options.options());
        ((ObjectNode) credential.get("response")).put("authenticatorData", "AAAA");

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
    }

    @Test
    void aDeactivatedAccountIsRefused() {
        final var user = signedIn(localUser("inactive@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        updated(user, account -> account.setActive(false));

        assertThatThrownBy(() -> signInWith(authenticator))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void anUnverifiedAccountIsRefused() {
        final var user = signedIn(localUser("unverified@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        updated(user, account -> account.setEmailVerified(false));

        assertThatThrownBy(() -> signInWith(authenticator))
                .isInstanceOf(EmailNotVerifiedException.class)
                .hasMessage("Please verify your email before logging in.");
    }

    @Test
    void aTwoStepAccountSignsInWithoutASecondStep() {
        final var user = signedIn(localUser("two-step@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        updated(user, account -> account.setTwoStepEnabled(true));

        final var response = signInWith(authenticator);

        assertThat(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER)).startsWith("Bearer ");
        assertThat(response.getBody()).satisfies(body -> {
            assertThat(body.getToken()).startsWith("Bearer ");
            assertThat(body.getUser().getTwoStepEnabled()).isTrue();
            assertThat(body.getTwoStepRequired()).isNull();
            assertThat(body.getPendingToken()).isNull();
        });
    }

    @Test
    void eachPasskeySignInAddsOneToTheDay() {
        signedIn(localUser("counted@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator, "MacBook");
        final var today = analyticsDays.today();
        final var before = signInsOn(today);

        signInWith(authenticator);
        assertThat(signInsOn(today)).isEqualTo(before + 1);
        signInWith(authenticator);
        assertThat(signInsOn(today)).isEqualTo(before + 2);
        assertThatThrownBy(() -> signInWith(authenticator.withoutUserVerification()))
                .isInstanceOf(PasskeyVerificationException.class);
        assertThat(signInsOn(today)).isEqualTo(before + 2);
    }

    @Test
    void aPasskeyThatStartsSyncingIsStoredAsBackedUp() {
        signedIn(localUser("starts-syncing@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        addPasskey(authenticator.notBackedUpYet(), "Pixel");
        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.isBackupEligible()).isTrue();
            assertThat(stored.isBackupState()).isFalse();
        });

        signInWith(authenticator);

        assertThat(stored(authenticator).isBackupState()).isTrue();
    }

    @Test
    void aDeviceBoundPasskeyCantBecomeBackupEligibleLater() {
        signedIn(localUser("never-eligible@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();
        addPasskey(authenticator, "Pixel");

        assertThatThrownBy(() -> signInWith(authenticator.nowBackedUp()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(stored(authenticator)).satisfies(stored -> {
            assertThat(stored.isBackupEligible()).isFalse();
            assertThat(stored.isBackupState()).isFalse();
        });
    }

    private PasskeyResponse addPasskey(final TestAuthenticator authenticator, final String label) {
        final var options = registrationOptions();

        return passkeyService.register(registration(options, authenticator.register(options.options()), label));
    }

    private PasskeyOptionsResponse registrationOptions() {
        return passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto(PASSWORD));
    }

    private static PasskeyRegistrationDto registration(final PasskeyOptionsResponse options, final JsonNode credential, final String label) {
        return PasskeyRegistrationDto.builder()
                .ceremonyId(options.ceremonyId())
                .credential(credential)
                .label(label)
                .build();
    }

    private ResponseEntity<JWTTokenDto> signInWith(final TestAuthenticator authenticator) {
        final var options = passkeySignInService.options(request());

        return passkeySignInService.verify(signIn(options, authenticator.authenticate(options.options())), request());
    }

    private static PasskeySignInDto signIn(final PasskeyOptionsResponse options, final JsonNode credential) {
        return PasskeySignInDto.builder()
                .ceremonyId(options.ceremonyId())
                .credential(credential)
                .rememberMe(true)
                .build();
    }

    private static MockHttpServletRequest request() {
        final var request = new MockHttpServletRequest();
        request.setRemoteAddr(CLIENT_IP);

        return request;
    }

    private void updated(final User user, final Consumer<User> change) {
        final var account = userRepository.findById(user.getId()).orElseThrow();
        change.accept(account);
        userRepository.saveAndFlush(account);
    }

    private long signInsOn(final LocalDate day) {
        return jdbcTemplate.queryForList("select sign_ins from passkey_sign_in_daily where stat_date = ?", Long.class, day)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    private PasskeyCredential stored(final TestAuthenticator authenticator) {
        entityManager.flush();
        entityManager.clear();

        return passkeyCredentialRepository.findByCredentialId(authenticator.credentialId()).orElseThrow();
    }

    private User signedIn(final User user) {
        when(userService.getUserFromToken()).thenReturn(user);

        return user;
    }

    private static User requestsViewOf(final User user) {
        return User.builder()
                .id(user.getId())
                .email(user.getEmail())
                .password(user.getPassword())
                .apiKeySlots(user.getApiKeySlots())
                .createDate(user.getCreateDate())
                .authProvider(user.getAuthProvider())
                .active(user.getActive())
                .emailVerified(user.getEmailVerified())
                .twoFactorEnabled(user.getTwoFactorEnabled())
                .twoStepEnabled(user.getTwoStepEnabled())
                .webauthnUserHandle(user.getWebauthnUserHandle())
                .build();
    }

    private User localUser(final String email) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .apiKeySlots(4L)
                .authProvider("local")
                .active(true)
                .emailVerified(true)
                .build());
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
    @EnableConfigurationProperties(AppProperties.class)
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, PasskeyCredentialRepository.class, TwoFactorRecoveryCodeRepository.class}))
    @Import({
            ClockConfiguration.class,
            PasskeyConfiguration.class,
            JpaUserCredentialRepository.class,
            JpaPublicKeyCredentialUserEntityRepository.class,
            PasskeyJson.class,
            PasskeyOptionsFactory.class,
            RedisPasskeyCeremonyStore.class,
            TokenProvider.class,
            RecentSignIn.class,
            DefaultAuthValidator.class,
            Reauthentication.class,
            DefaultPasskeyService.class,
            RedisPendingLoginStore.class,
            TwoStepMethods.class,
            UserToUserDtoConverter.class,
            DefaultSignInService.class,
            ClientIpResolver.class,
            AnalyticsDays.class,
            PasskeySignInStatsRepository.class,
            PasskeyAssertions.class,
            DefaultPasskeySignInService.class
    })
    static class TestApplication {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

    }

}
