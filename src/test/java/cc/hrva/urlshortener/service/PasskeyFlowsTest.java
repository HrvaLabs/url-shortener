package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.PasskeyConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.dto.PasskeySignInDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.listener.PasskeyAddedEmailListener;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
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
import cc.hrva.urlshortener.service.impl.DefaultSecondStepService;
import cc.hrva.urlshortener.service.impl.DefaultSignInService;
import cc.hrva.urlshortener.service.impl.DefaultTwoFactorService;
import cc.hrva.urlshortener.service.impl.DefaultTwoStepSettingsService;
import cc.hrva.urlshortener.service.passkey.JpaPublicKeyCredentialUserEntityRepository;
import cc.hrva.urlshortener.service.passkey.JpaUserCredentialRepository;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.passkey.RedisPasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.TestAuthenticator;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.service.twostep.RecoveryCodes;
import cc.hrva.urlshortener.service.twostep.RedisPendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepLockMessage;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import cc.hrva.urlshortener.validator.impl.DefaultAuthValidator;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.persistence.EntityManager;
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
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.CLIENT_IP;
import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.registration;
import static cc.hrva.urlshortener.service.PasskeyFlowsSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    private static final String EXPIRED = "This request expired. Try again.";
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String NOT_REGISTERED =
            "This passkey isn't registered on hrva.cc. Remove it from your password manager or device, or sign in another way.";
    private static final RSAKey SIGNING_KEY = PasskeyFlowsSupport.generateSigningKey();

    private final InMemoryRedis redis = new InMemoryRedis();
    private PasskeyFlowsSupport flows;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        flows = new PasskeyFlowsSupport(userRepository, passwordEncoder, passkeyService, passkeyCredentialRepository,
                entityManager, jdbcTemplate, userService);
    }

    @Test
    void registersASyncedPasskey() {
        final var user = flows.signedIn(flows.localUser("synced@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();

        final var passkey = flows.addPasskey(authenticator, "MacBook");

        assertThat(passkey.id()).isNotNull();
        assertThat(passkey.label()).isEqualTo("MacBook");
        assertThat(passkey.kind()).isEqualTo(PasskeyKind.SYNCED);
        assertThat(passkey.backupEligible()).isTrue();
        assertThat(passkey.backedUp()).isTrue();
        assertThat(passkey.discoverable()).isTrue();
        assertThat(passkey.transports()).containsExactly("hybrid", "internal");
        assertThat(passkey.createdAt()).isNotNull();
        assertThat(passkey.lastUsedAt()).isNull();
        assertThat(flows.stored(authenticator)).satisfies(stored -> {
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
        flows.signedIn(flows.localUser("device@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();

        final var passkey = flows.addPasskey(authenticator, "Pixel");

        assertThat(passkey.kind()).isEqualTo(PasskeyKind.DEVICE_BOUND);
        assertThat(passkey.backupEligible()).isFalse();
        assertThat(passkey.discoverable()).isTrue();
        assertThat(passkey.transports()).containsExactly("internal");
        assertThat(flows.stored(authenticator).getSignCount()).isEqualTo(1L);
    }

    @Test
    void registersANonDiscoverableSecurityKeyAsSecondStepOnly() {
        flows.signedIn(flows.localUser("key@example.com"));
        final var authenticator = TestAuthenticator.securityKey();

        final var passkey = flows.addPasskey(authenticator, "YubiKey");

        assertThat(passkey.kind()).isEqualTo(PasskeyKind.SECOND_STEP_ONLY);
        assertThat(passkey.discoverable()).isFalse();
        assertThat(passkey.backupEligible()).isFalse();
        assertThat(passkey.backedUp()).isFalse();
        assertThat(passkey.transports()).containsExactly("usb", "nfc");
        assertThat(flows.stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getTransports()).isEqualTo("usb,nfc");
            assertThat(stored.isUvInitialized()).isFalse();
            assertThat(stored.getSignCount()).isEqualTo(1L);
        });
    }

    @Test
    void aCredentialWithoutTransportsRegisters() {
        flows.signedIn(flows.localUser("no-transports@example.com"));
        final var authenticator = TestAuthenticator.securityKey().withoutTransports();

        final var passkey = flows.addPasskey(authenticator, "Old key");

        assertThat(passkey.transports()).isEmpty();
        assertThat(flows.stored(authenticator).getTransports()).isNull();
        assertThat(flows.registrationOptions().options().path("excludeCredentials").values())
                .singleElement()
                .satisfies(descriptor -> {
                    assertThat(descriptor.path("id").asString()).isEqualTo(authenticator.credentialId());
                    assertThat(descriptor.path("transports").values()).isEmpty();
                });
    }

    @Test
    void anUnknownTransportIsDroppedAndTheKnownOneStored() {
        flows.signedIn(flows.localUser("cable@example.com"));
        final var authenticator = TestAuthenticator.securityKey().withTransports("cable", "usb");

        final var passkey = flows.addPasskey(authenticator, "Phone");

        assertThat(passkey.transports()).containsExactly("usb");
        assertThat(flows.stored(authenticator).getTransports()).isEqualTo("usb");
    }

    @Test
    void anAuthenticatorWithoutUserPresenceIsRefused() {
        flows.signedIn(flows.localUser("presence@example.com"));
        final var options = flows.registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().withoutUserPresence().register(options.options());

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aCeremonyCanBeUsedOnce() {
        flows.signedIn(flows.localUser("once@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        final var options = flows.registrationOptions();
        final var credential = authenticator.register(options.options());
        passkeyService.register(registration(options, credential, "MacBook"));

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        assertThat(passkeyCredentialRepository.count()).isEqualTo(1L);
    }

    @Test
    void anotherUsersCeremonyIsRefused() {
        flows.signedIn(flows.localUser("alice@example.com"));
        final var alicesOptions = flows.registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().register(alicesOptions.options());
        flows.signedIn(flows.localUser("bob@example.com"));

        assertThatThrownBy(() -> passkeyService.register(registration(alicesOptions, credential, "Not mine")))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aWrongOriginIsRefused() {
        flows.signedIn(flows.localUser("origin@example.com"));
        final var options = flows.registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().withOrigin("https://evil.example").register(options.options());

        assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aResponseToAnotherChallengeIsRefused() {
        flows.signedIn(flows.localUser("challenge@example.com"));
        final var answered = flows.registrationOptions();
        final var sent = flows.registrationOptions();
        final var credential = TestAuthenticator.syncedPasskey().register(answered.options());

        assertThatThrownBy(() -> passkeyService.register(registration(sent, credential, "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void aCredentialForAnotherSiteIsRefused() {
        flows.signedIn(flows.localUser("site@example.com"));
        final var options = flows.registrationOptions();
        final var otherSite = options.options().deepCopy();
        ((ObjectNode) otherSite.get("rp")).put("id", "evil.example");

        assertThatThrownBy(() -> passkeyService.register(registration(options, TestAuthenticator.syncedPasskey().register(otherSite), "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(passkeyCredentialRepository.count()).isZero();
    }

    @Test
    void theSameAuthenticatorCantBeAddedTwice() {
        flows.signedIn(flows.localUser("twice@example.com"));
        final var authenticator = TestAuthenticator.securityKey();
        flows.addPasskey(authenticator, "YubiKey");
        final var options = flows.registrationOptions();
        assertThat(options.options().path("excludeCredentials").values())
                .extracting(descriptor -> descriptor.path("id").asString())
                .containsExactly(authenticator.credentialId());

        assertThatThrownBy(() -> passkeyService.register(registration(options, authenticator.register(options.options()), "Again")))
                .isInstanceOf(ApiException.class)
                .hasMessage("This passkey or security key is already added.");
        assertThat(passkeyCredentialRepository.count()).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void addingAPasskeySendsTheSecurityNotice() {
        final var user = flows.signedIn(flows.localUser("notice@example.com"));
        try {
            final var passkey = flows.addPasskey(TestAuthenticator.syncedPasskey(), "  MacBook  ");

            verify(sendingEmailService).sendPasskeyAddedEmail(user, "MacBook", passkey.createdAt());
        } finally {
            flows.remove(user);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theSecurityNoticeWaitsForTheCommit() {
        final var user = flows.signedIn(flows.localUser("notice-after-commit@example.com"));
        try {
            final var options = flows.registrationOptions();
            final var credential = TestAuthenticator.securityKey().register(options.options());

            final var passkey = new TransactionTemplate(transactionManager).execute(status -> {
                final var added = passkeyService.register(registration(options, credential, "YubiKey"));
                verify(sendingEmailService, never()).sendPasskeyAddedEmail(any(), any(), any());

                return added;
            });

            verify(sendingEmailService).sendPasskeyAddedEmail(user, "YubiKey", passkey.createdAt());
        } finally {
            flows.remove(user);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedRegistrationSendsNothing() {
        final var user = flows.signedIn(flows.localUser("refused-notice@example.com"));
        try {
            final var options = flows.registrationOptions();
            final var credential = TestAuthenticator.syncedPasskey().withOrigin("https://evil.example").register(options.options());

            assertThatThrownBy(() -> passkeyService.register(registration(options, credential, "MacBook")))
                    .isInstanceOf(PasskeyVerificationException.class)
                    .hasMessage(COULD_NOT_VERIFY);

            verify(sendingEmailService, never()).sendPasskeyAddedEmail(any(), any(), any());
        } finally {
            flows.remove(user);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRegistrationRolledBackAfterItsSaveSendsNothing() {
        final var user = flows.signedIn(flows.localUser("rolled-back-notice@example.com"));
        try {
            final var authenticator = TestAuthenticator.syncedPasskey();
            final var options = flows.registrationOptions();
            final var credential = authenticator.register(options.options());

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                passkeyService.register(registration(options, credential, "MacBook"));
                status.setRollbackOnly();
            });

            assertThat(passkeyCredentialRepository.findByCredentialId(authenticator.credentialId())).isEmpty();
            verify(sendingEmailService, never()).sendPasskeyAddedEmail(any(), any(), any());
        } finally {
            flows.remove(user);
        }
    }

    @Test
    void aLabelOver64CharactersIs400() {
        flows.signedIn(flows.localUser("label@example.com"));
        final var options = flows.registrationOptions();
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
        flows.signedIn(flows.localUser("blank@example.com"));

        assertThat(flows.addPasskey(TestAuthenticator.syncedPasskey(), "   ").label()).isEqualTo("Passkey");
        assertThat(flows.addPasskey(TestAuthenticator.securityKey(), null).label()).isEqualTo("Passkey");
    }

    @Test
    void theUserHandleIsCreatedOnceAndKept() {
        final var user = flows.signedIn(flows.localUser("handle@example.com"));

        final var first = flows.registrationOptions().options().path("user").path("id").asString();
        final var second = flows.registrationOptions().options().path("user").path("id").asString();

        assertThat(first).hasSize(43).isEqualTo(second);
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getWebauthnUserHandle()).isEqualTo(first);
    }

    @Test
    void twoFirstRequestsRacingGetTheSameHandle() {
        final var user = flows.localUser("race@example.com");
        when(userService.getUserFromToken()).thenReturn(requestsViewOf(user), requestsViewOf(user));

        final var first = flows.registrationOptions().options().path("user").path("id").asString();
        final var second = flows.registrationOptions().options().path("user").path("id").asString();

        assertThat(first).hasSize(43);
        assertThat(second).isEqualTo(first);
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getWebauthnUserHandle()).isEqualTo(first);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDiscoverablePasskeySignsInWithoutAPassword() {
        final var user = flows.signedIn(flows.localUser("passwordless@example.com"));
        try {
            final var authenticator = TestAuthenticator.deviceBoundPasskey();
            flows.addPasskey(authenticator, "Pixel");

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
            flows.remove(user);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAssertionWithoutAReadWriteTransactionIsRefusedBeforeItIsRead() {
        final var ceremony = new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, Bytes.random().toBase64UrlString(), null);
        final var unreadable = JsonMapper.builder().build().createObjectNode();
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> passkeyAssertions.verify(ceremony, unreadable, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> passkeyAssertions.verify(ceremony, unreadable, null)))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessage("Passkey assertions need a read-write transaction");
    }

    @Test
    void aSyncedPasskeyReportingZeroTwiceStillSignsIn() {
        final var user = flows.signedIn(flows.localUser("zero@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");

        assertThat(signInWith(authenticator).getBody().getUser().getId()).isEqualTo(user.getId());
        assertThat(signInWith(authenticator).getBody().getUser().getId()).isEqualTo(user.getId());

        assertThat(flows.stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getSignCount()).isZero();
            assertThat(stored.getLastUsedAt()).isNotNull();
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCounterThatDidNotIncreaseIsRefusedAndLoggedWithoutTheFullCredentialId(final CapturedOutput output) {
        final var user = flows.signedIn(flows.localUser("cloned@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();
        flows.addPasskey(authenticator, "Pixel");
        final var credentialId = authenticator.credentialId();

        assertThatThrownBy(() -> signInWith(authenticator.withNextCounter(1)))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);

        assertThat(output)
                .contains("Passkey sign count did not increase user id=" + user.getId() + " credential="
                        + credentialId.substring(0, 8) + "… stored=1 presented=1")
                .doesNotContain(credentialId);
        assertThat(flows.stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getSignCount()).isEqualTo(1L);
            assertThat(stored.getLastUsedAt()).isNull();
        });
        verify(loginAttemptService).loginFailed(CLIENT_IP);
    }

    @Test
    void aCounterDroppingToZeroIsAccepted() {
        flows.signedIn(flows.localUser("reset@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey().withNextCounter(5);
        flows.addPasskey(authenticator, "Pixel");

        assertThat(signInWith(authenticator.withNextCounter(0)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(flows.stored(authenticator)).satisfies(stored -> {
            assertThat(stored.getSignCount()).isEqualTo(5L);
            assertThat(stored.getLastUsedAt()).isNotNull();
        });
    }

    @Test
    void aPasskeyWithoutUserVerificationIsRefusedForPasswordlessSignIn() {
        flows.signedIn(flows.localUser("no-uv@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");

        assertThatThrownBy(() -> signInWith(authenticator.withoutUserVerification()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(flows.stored(authenticator).getLastUsedAt()).isNull();
    }

    @Test
    void aPasskeyDeletedBetweenOptionsAndVerifyIsA401NotA500() {
        flows.signedIn(flows.localUser("deleted@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        final var credential = authenticator.authenticate(options.options());
        passkeyCredentialRepository.delete(flows.stored(authenticator));
        entityManager.flush();

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(NOT_REGISTERED);
    }

    @Test
    void aReplayedCeremonyIsRefused() {
        flows.signedIn(flows.localUser("replay@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        passkeySignInService.verify(signIn(options, authenticator.authenticate(options.options())), request());

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, authenticator.authenticate(options.options())), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
    }

    @Test
    void aRegistrationCeremonyCantBeUsedToSignIn() {
        flows.signedIn(flows.localUser("wrong-ceremony@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        final var registration = flows.registrationOptions();
        final var requestShaped = JsonMapper.builder().build().createObjectNode()
                .put("challenge", registration.options().path("challenge").asString())
                .put("rpId", TestAuthenticator.RP_ID);

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(registration, authenticator.authenticate(requestShaped)), request()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);
        assertThat(flows.stored(authenticator).getLastUsedAt()).isNull();
    }

    @Test
    void aMismatchedUserHandleIsRefused() {
        flows.signedIn(flows.localUser("alice-handle@example.com"));
        final var alicesPasskey = TestAuthenticator.syncedPasskey();
        flows.addPasskey(alicesPasskey, "Alice");
        final var bob = flows.signedIn(flows.localUser("bob-handle@example.com"));
        final var bobsHandle = flows.registrationOptions().options().path("user").path("id").asString();
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
        flows.signedIn(flows.localUser("no-handle@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        final var credential = authenticator.authenticate(options.options());
        ((ObjectNode) credential.get("response")).remove("userHandle");

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
    }

    @Test
    void aTruncatedAuthenticatorDataIsA401NotA500() {
        flows.signedIn(flows.localUser("truncated@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        final var options = passkeySignInService.options(request());
        final var credential = authenticator.authenticate(options.options());
        ((ObjectNode) credential.get("response")).put("authenticatorData", "AAAA");

        assertThatThrownBy(() -> passkeySignInService.verify(signIn(options, credential), request()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
    }

    @Test
    void aDeactivatedAccountIsRefused() {
        final var user = flows.signedIn(flows.localUser("inactive@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        flows.updated(user, account -> account.setActive(false));

        assertThatThrownBy(() -> signInWith(authenticator))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void anUnverifiedAccountIsRefused() {
        final var user = flows.signedIn(flows.localUser("unverified@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        flows.updated(user, account -> account.setEmailVerified(false));

        assertThatThrownBy(() -> signInWith(authenticator))
                .isInstanceOf(EmailNotVerifiedException.class)
                .hasMessage("Please verify your email before logging in.");
    }

    @Test
    void aTwoStepAccountSignsInWithoutASecondStep() {
        final var user = flows.signedIn(flows.localUser("two-step@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        flows.updated(user, account -> account.setTwoStepEnabled(true));

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
        flows.signedIn(flows.localUser("counted@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator, "MacBook");
        final var today = analyticsDays.today();
        final var before = flows.signInsOn(today);

        signInWith(authenticator);
        assertThat(flows.signInsOn(today)).isEqualTo(before + 1);
        signInWith(authenticator);
        assertThat(flows.signInsOn(today)).isEqualTo(before + 2);
        assertThatThrownBy(() -> signInWith(authenticator.withoutUserVerification()))
                .isInstanceOf(PasskeyVerificationException.class);
        assertThat(flows.signInsOn(today)).isEqualTo(before + 2);
    }

    @Test
    void aPasskeyThatStartsSyncingIsStoredAsBackedUp() {
        flows.signedIn(flows.localUser("starts-syncing@example.com"));
        final var authenticator = TestAuthenticator.syncedPasskey();
        flows.addPasskey(authenticator.notBackedUpYet(), "Pixel");
        assertThat(flows.stored(authenticator)).satisfies(stored -> {
            assertThat(stored.isBackupEligible()).isTrue();
            assertThat(stored.isBackupState()).isFalse();
        });

        signInWith(authenticator);

        assertThat(flows.stored(authenticator).isBackupState()).isTrue();
    }

    @Test
    void aDeviceBoundPasskeyCantBecomeBackupEligibleLater() {
        flows.signedIn(flows.localUser("never-eligible@example.com"));
        final var authenticator = TestAuthenticator.deviceBoundPasskey();
        flows.addPasskey(authenticator, "Pixel");

        assertThatThrownBy(() -> signInWith(authenticator.nowBackedUp()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY);
        assertThat(flows.stored(authenticator)).satisfies(stored -> {
            assertThat(stored.isBackupEligible()).isFalse();
            assertThat(stored.isBackupState()).isFalse();
        });
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
            PasskeyCeremonies.class,
            TokenProvider.class,
            RecentSignIn.class,
            DefaultAuthValidator.class,
            Reauthentication.class,
            DefaultPasskeyService.class,
            PasskeyAddedEmailListener.class,
            RedisPendingLoginStore.class,
            TwoStepMethods.class,
            TwoStepLockMessage.class,
            UserToUserDtoConverter.class,
            DefaultSignInService.class,
            ClientIpResolver.class,
            AnalyticsDays.class,
            PasskeySignInStatsRepository.class,
            PasskeyAssertions.class,
            DefaultPasskeySignInService.class,
            RedisFixedWindowRateLimiter.class,
            DefaultTwoFactorService.class,
            DefaultSecondStepService.class,
            RecoveryCodes.class,
            DefaultTwoStepSettingsService.class,
            AccountLock.class
    })
    static class TestApplication {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

    }

}
