package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.PasskeyConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.RecentSignIn;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.impl.DefaultPasskeyService;
import cc.hrva.urlshortener.service.impl.DefaultSignInService;
import cc.hrva.urlshortener.service.passkey.JpaPublicKeyCredentialUserEntityRepository;
import cc.hrva.urlshortener.service.passkey.JpaUserCredentialRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    private final InMemoryRedis redis = new InMemoryRedis();

    @Autowired
    private PasskeyService passkeyService;

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

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        redis.backing(redisTemplate);
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
            DefaultSignInService.class
    })
    static class TestApplication {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

    }

}
