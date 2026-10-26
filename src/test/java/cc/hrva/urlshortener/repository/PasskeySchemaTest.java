package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@SpringBootTest(
        classes = PasskeySchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:passkey-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class PasskeySchemaTest {

    private static final LocalDateTime ADDED_AT = LocalDateTime.of(2026, 10, 25, 14, 5);
    private static final String USER_HANDLE = "q83vEjRWeJq83vEjRWeJq83vEjRWeJq83vEjRWeJq80";

    @Autowired
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void storesAndReadsBackAPasskeyWithALongAttestationObject() {
        final var credentialId = base64Url(1023);
        final var attestationObject = base64Url(2250);
        assertThat(credentialId).hasSize(1364);
        assertThat(attestationObject).hasSize(3000);
        final var owner = savedUser("alice@example.com");
        passkeyCredentialRepository.saveAndFlush(passkey(owner, credentialId)
                .publicKey("pQECAyYgASFYIBrS")
                .attestationObject(attestationObject)
                .build());
        entityManager.clear();

        assertThat(passkeyCredentialRepository.findByCredentialId(credentialId)).hasValueSatisfying(stored -> {
            assertThat(stored.getCredentialId()).isEqualTo(credentialId);
            assertThat(stored.getAttestationObject()).isEqualTo(attestationObject);
            assertThat(stored.getPublicKey()).isEqualTo("pQECAyYgASFYIBrS");
            assertThat(stored.getTransports()).isEqualTo("internal,usb");
            assertThat(stored.getAaguid()).isEqualTo("fbfc3007-154e-4ecc-8c0b-6e020557d7bd");
            assertThat(stored.getLabel()).isEqualTo("MacBook");
            assertThat(stored.getDiscoverable()).isNull();
            assertThat(stored.getCreatedAt()).isEqualTo(ADDED_AT);
            assertThat(stored.getLastUsedAt()).isNull();
        });
    }

    @Test
    void aCredentialIdIsUniqueAcrossAccounts() {
        passkeyCredentialRepository.saveAndFlush(passkey(savedUser("alice@example.com"), "c2FtZS1jcmVkZW50aWFs").build());
        final var sameCredentialForBob = passkey(savedUser("bob@example.com"), "c2FtZS1jcmVkZW50aWFs").build();

        assertThatThrownBy(() -> passkeyCredentialRepository.saveAndFlush(sameCredentialForBob))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheUserRowDeletesItsPasskeys() {
        final var alice = savedUser("alice@example.com");
        final var bob = savedUser("bob@example.com");
        passkeyCredentialRepository.saveAndFlush(passkey(alice, "YWxpY2UtcGhvbmU").build());
        passkeyCredentialRepository.saveAndFlush(passkey(alice, "YWxpY2Uta2V5").build());
        passkeyCredentialRepository.saveAndFlush(passkey(bob, "Ym9iLXBob25l").build());

        jdbcTemplate.update("delete from user_account where id = ?", alice.getId());

        assertThat(passkeyCount(alice)).isZero();
        assertThat(passkeyCount(bob)).isEqualTo(1L);
    }

    @Test
    void userHandlesAreUnique() {
        userRepository.saveAndFlush(user("alice@example.com").webauthnUserHandle(USER_HANDLE).build());
        final var bob = user("bob@example.com").webauthnUserHandle(USER_HANDLE).build();

        assertThatThrownBy(() -> userRepository.saveAndFlush(bob)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newUsersStartWithTwoStepOff() {
        final var user = userRepository.saveAndFlush(user("alice@example.com").build());
        entityManager.clear();

        assertThat(userRepository.findById(user.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getTwoStepEnabled()).isFalse();
            assertThat(stored.getWebauthnUserHandle()).isNull();
        });
    }

    @Test
    void oneSignInRowPerDay() {
        final var day = LocalDate.of(2026, 10, 25);
        jdbcTemplate.update("insert into passkey_sign_in_daily (stat_date) values (?)", day);
        assertThat(jdbcTemplate.queryForObject("select sign_ins from passkey_sign_in_daily where stat_date = ?", Long.class, day)).isZero();

        assertThatThrownBy(() -> jdbcTemplate.update("insert into passkey_sign_in_daily (stat_date) values (?)", day))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "false, true, SECOND_STEP_ONLY",
            "false, false, SECOND_STEP_ONLY",
            "true, true, SYNCED",
            "null, true, SYNCED",
            "true, false, DEVICE_BOUND",
            "null, false, UNKNOWN"
    })
    void kindFollowsDiscoverableThenBackupEligibility(final Boolean discoverable, final boolean backupEligible, final PasskeyKind kind) {
        final var passkey = PasskeyCredential.builder().discoverable(discoverable).backupEligible(backupEligible).build();

        assertThat(passkey.kind()).isEqualTo(kind);
    }

    private long passkeyCount(final User user) {
        return jdbcTemplate.queryForObject("select count(*) from passkey_credential where user_id = ?", Long.class, user.getId());
    }

    private User savedUser(final String email) {
        return userRepository.saveAndFlush(user(email).build());
    }

    private static User.UserBuilder user(final String email) {
        return User.builder().email(email).password("correct horse battery").apiKeySlots(4L);
    }

    private static PasskeyCredential.PasskeyCredentialBuilder passkey(final User owner, final String credentialId) {
        return PasskeyCredential.builder()
                .user(owner)
                .credentialId(credentialId)
                .publicKey("pQECAyYgASFY")
                .attestationObject("o2NmbXRkbm9uZQ")
                .uvInitialized(true)
                .backupEligible(true)
                .backupState(true)
                .transports("internal,usb")
                .aaguid("fbfc3007-154e-4ecc-8c0b-6e020557d7bd")
                .label("MacBook")
                .createdAt(ADDED_AT);
    }

    private static String base64Url(final int byteCount) {
        final var bytes = new byte[byteCount];
        for (var index = 0; index < byteCount; index++) {
            bytes[index] = (byte) (index * 31 + 7);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
    }

}
