package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import jakarta.persistence.EntityManager;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = JpaUserCredentialRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:passkey-credentials;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class JpaUserCredentialRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 14, 5);
    private static final String ICLOUD_KEYCHAIN = "fbfc3007-154e-4ecc-8c0b-6e020557d7bd";
    private static final ObjectConverter OBJECT_CONVERTER = new ObjectConverter();

    @Autowired
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    private JpaUserCredentialRepository credentials;
    private User owner;
    private Bytes handle;

    @BeforeEach
    void setUp() {
        credentials = new JpaUserCredentialRepository(passkeyCredentialRepository, userRepository, clock);
        handle = Bytes.random();
        owner = userRepository.saveAndFlush(User.builder()
                .email("owner@example.com")
                .password("correct horse battery")
                .apiKeySlots(4L)
                .webauthnUserHandle(handle.toBase64UrlString())
                .build());
    }

    @Test
    void savingANewRecordStoresEveryColumnAndNoLastUse() {
        final var credentialId = Bytes.random();
        final var publicKey = new byte[] {1, 2, 3, 4};
        final var attestationObject = attestationObject(credentialId, new AAGUID(ICLOUD_KEYCHAIN));

        credentials.save(record(credentialId, attestationObject)
                .publicKey(new ImmutablePublicKeyCose(publicKey))
                .signatureCount(3)
                .build());

        assertThat(stored(credentialId)).satisfies(stored -> {
            assertThat(stored.getUser().getId()).isEqualTo(owner.getId());
            assertThat(stored.getCredentialId()).isEqualTo(credentialId.toBase64UrlString());
            assertThat(stored.getPublicKey()).isEqualTo(new Bytes(publicKey).toBase64UrlString());
            assertThat(stored.getAttestationObject()).isEqualTo(attestationObject.toBase64UrlString());
            assertThat(stored.getSignCount()).isEqualTo(3L);
            assertThat(stored.isUvInitialized()).isTrue();
            assertThat(stored.isBackupEligible()).isTrue();
            assertThat(stored.isBackupState()).isFalse();
            assertThat(stored.getDiscoverable()).isNull();
            assertThat(stored.getTransports()).isEqualTo("hybrid,internal");
            assertThat(stored.getAaguid()).isEqualTo(ICLOUD_KEYCHAIN);
            assertThat(stored.getLabel()).isEqualTo("MacBook");
            assertThat(stored.getCreatedAt()).isEqualTo(NOW);
            assertThat(stored.getLastUsedAt()).isNull();
        });
    }

    @Test
    void transportsAreStoredInTheLibrarysOrder() {
        final var credentialId = Bytes.random();

        credentials.save(record(credentialId, attestationObject(credentialId, AAGUID.ZERO))
                .transports(Set.of(AuthenticatorTransport.NFC, AuthenticatorTransport.USB))
                .build());

        assertThat(stored(credentialId).getTransports()).isEqualTo("usb,nfc");
    }

    @Test
    void savingAnExistingRecordUpdatesTheCountAndLastUse() {
        final var credentialId = Bytes.random();
        final var existing = existingPasskey(credentialId, 5);

        credentials.save(record(credentialId, attestationObject(credentialId, AAGUID.ZERO))
                .signatureCount(9)
                .label("Renamed by the library")
                .build());

        assertThat(stored(credentialId)).satisfies(stored -> {
            assertThat(stored.getId()).isEqualTo(existing.getId());
            assertThat(stored.getSignCount()).isEqualTo(9L);
            assertThat(stored.getLastUsedAt()).isEqualTo(NOW);
            assertThat(stored.getCreatedAt()).isEqualTo(NOW.minusDays(3));
            assertThat(stored.getLabel()).isEqualTo("YubiKey");
        });
        assertThat(passkeyCredentialRepository.count()).isEqualTo(1L);
    }

    @Test
    void theStoredCountNeverGoesDown() {
        final var credentialId = Bytes.random();
        existingPasskey(credentialId, 5);

        credentials.save(record(credentialId, attestationObject(credentialId, AAGUID.ZERO)).signatureCount(0).build());

        assertThat(stored(credentialId).getSignCount()).isEqualTo(5L);
        assertThat(stored(credentialId).getLastUsedAt()).isEqualTo(NOW);
    }

    @Test
    void readsBackTheAttestationObjectSpringNeeds() {
        final var credentialId = Bytes.random();
        final var attestationObject = attestationObject(credentialId, AAGUID.ZERO);
        final var publicKey = new byte[] {9, 8, 7};
        credentials.save(record(credentialId, attestationObject).publicKey(new ImmutablePublicKeyCose(publicKey)).build());
        entityManager.flush();
        entityManager.clear();

        final CredentialRecord read = credentials.findByCredentialId(credentialId);

        assertThat(read.getCredentialType()).isEqualTo(PublicKeyCredentialType.PUBLIC_KEY);
        assertThat(read.getCredentialId()).isEqualTo(credentialId);
        assertThat(read.getUserEntityUserId()).isEqualTo(handle);
        assertThat(read.getPublicKey().getBytes()).isEqualTo(publicKey);
        assertThat(read.getAttestationObject()).isEqualTo(attestationObject);
        assertThat(read.getSignatureCount()).isEqualTo(2L);
        assertThat(read.isUvInitialized()).isTrue();
        assertThat(read.isBackupEligible()).isTrue();
        assertThat(read.isBackupState()).isFalse();
        assertThat(read.getTransports()).containsExactly(AuthenticatorTransport.HYBRID, AuthenticatorTransport.INTERNAL);
        assertThat(read.getLabel()).isEqualTo("MacBook");
        assertThat(read.getCreated()).isEqualTo(NOW.toInstant(ZoneOffset.UTC));
        assertThat(read.getLastUsed()).isEqualTo(read.getCreated());
        final var parsed = OBJECT_CONVERTER.getCborConverter().readValue(read.getAttestationObject().getBytes(), AttestationObject.class);
        assertThat(parsed.getAuthenticatorData().getAttestedCredentialData().getCredentialId()).isEqualTo(credentialId.getBytes());
    }

    @Test
    void anAllZeroAaguidIsStoredAsNull() {
        final var credentialId = Bytes.random();

        credentials.save(record(credentialId, attestationObject(credentialId, AAGUID.ZERO)).build());

        assertThat(stored(credentialId).getAaguid()).isNull();
    }

    @Test
    void findsTheOwnersCredentialsByHandleAndNothingForAnUnknownOne() {
        final var credentialId = Bytes.random();
        credentials.save(record(credentialId, attestationObject(credentialId, AAGUID.ZERO)).build());

        assertThat(credentials.findByUserId(handle)).extracting(CredentialRecord::getCredentialId).containsExactly(credentialId);
        assertThat(credentials.findByUserId(Bytes.random())).isEmpty();
        assertThat(credentials.findByCredentialId(Bytes.random())).isNull();
    }

    @Test
    void deleteRemovesTheCredential() {
        final var credentialId = Bytes.random();
        credentials.save(record(credentialId, attestationObject(credentialId, AAGUID.ZERO)).build());

        credentials.delete(credentialId);

        assertThat(passkeyCredentialRepository.findByCredentialId(credentialId.toBase64UrlString())).isEmpty();
    }

    private PasskeyCredential existingPasskey(final Bytes credentialId, final long signCount) {
        return passkeyCredentialRepository.saveAndFlush(PasskeyCredential.builder()
                .user(owner)
                .credentialId(credentialId.toBase64UrlString())
                .publicKey("pQECAyYgASFY")
                .attestationObject(attestationObject(credentialId, AAGUID.ZERO).toBase64UrlString())
                .signCount(signCount)
                .uvInitialized(true)
                .transports("usb")
                .label("YubiKey")
                .createdAt(NOW.minusDays(3))
                .build());
    }

    private PasskeyCredential stored(final Bytes credentialId) {
        entityManager.flush();
        entityManager.clear();

        return passkeyCredentialRepository.findByCredentialId(credentialId.toBase64UrlString()).orElseThrow();
    }

    private ImmutableCredentialRecord.ImmutableCredentialRecordBuilder record(final Bytes credentialId, final Bytes attestationObject) {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(credentialId)
                .userEntityUserId(handle)
                .publicKey(new ImmutablePublicKeyCose(new byte[] {1}))
                .signatureCount(2)
                .uvInitialized(true)
                .transports(Set.of(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID))
                .backupEligible(true)
                .backupState(false)
                .attestationObject(attestationObject)
                .label("MacBook");
    }

    private static Bytes attestationObject(final Bytes credentialId, final AAGUID aaguid) {
        try {
            final var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            final var attestedCredentialData = new AttestedCredentialData(aaguid, credentialId.getBytes(),
                    EC2COSEKey.create(generator.generateKeyPair(), COSEAlgorithmIdentifier.ES256));
            final var authenticatorData = new AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(new byte[32],
                    (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_AT), 0, attestedCredentialData);

            return new Bytes(OBJECT_CONVERTER.getCborConverter()
                    .writeValueAsBytes(new AttestationObject(authenticatorData, new NoneAttestationStatement())));
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = PasskeyCredential.class)
    @EnableJpaRepositories(
            basePackageClasses = PasskeyCredentialRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {PasskeyCredentialRepository.class, UserRepository.class}))
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

    }

}
