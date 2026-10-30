package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.passkey.TestAuthenticator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.when;

final class PasskeyFlowsSupport {

    static final String PASSWORD = "user123";
    static final String CLIENT_IP = "203.0.113.9";
    private static final long ROLE_USER = 1L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasskeyService passkeyService;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;

    PasskeyFlowsSupport(
            final UserRepository userRepository,
            final PasswordEncoder passwordEncoder,
            final PasskeyService passkeyService,
            final PasskeyCredentialRepository passkeyCredentialRepository,
            final EntityManager entityManager,
            final JdbcTemplate jdbcTemplate,
            final UserService userService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passkeyService = passkeyService;
        this.passkeyCredentialRepository = passkeyCredentialRepository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
    }

    User localUser(final String email) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .apiKeySlots(4L)
                .authProvider("local")
                .active(true)
                .emailVerified(true)
                .build());
    }

    User withTheUserRole(final User user) {
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", user.getId(), ROLE_USER);

        return user;
    }

    User signedIn(final User user) {
        when(userService.getUserFromToken()).thenReturn(user);

        return user;
    }

    User signedInAgain(final User user) {
        return signedIn(userRepository.findById(user.getId()).orElseThrow());
    }

    PasskeyResponse addPasskey(final TestAuthenticator authenticator, final String label) {
        final var options = registrationOptions();

        return passkeyService.register(registration(options, authenticator.register(options.options()), label));
    }

    PasskeyOptionsResponse registrationOptions() {
        return passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto(PASSWORD));
    }

    PasskeyCredential stored(final TestAuthenticator authenticator) {
        entityManager.flush();
        entityManager.clear();

        return passkeyCredentialRepository.findByCredentialId(authenticator.credentialId()).orElseThrow();
    }

    void updated(final User user, final Consumer<User> change) {
        final var account = userRepository.findById(user.getId()).orElseThrow();
        change.accept(account);
        userRepository.saveAndFlush(account);
    }

    long signInsOn(final LocalDate day) {
        return jdbcTemplate.queryForList("select sign_ins from passkey_sign_in_daily where stat_date = ?", Long.class, day)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    List<String> signInMethodsOf(final User user) {
        return jdbcTemplate.queryForList("select method from user_sign_in_method where user_id = ? order by method", String.class, user.getId());
    }

    void remove(final User user) {
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", user.getId());
        jdbcTemplate.update("delete from two_factor_recovery_code where user_id = ?", user.getId());
        passkeyCredentialRepository.deleteAll(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user));
        userRepository.deleteById(user.getId());
    }

    static PasskeyRegistrationDto registration(final PasskeyOptionsResponse options, final JsonNode credential, final String label) {
        return PasskeyRegistrationDto.builder()
                .ceremonyId(options.ceremonyId())
                .credential(credential)
                .label(label)
                .build();
    }

    static List<String> rolesWrittenIn(final ResponseEntity<JWTTokenDto> response) {
        return JSON.readTree(JSON.writeValueAsString(response.getBody())).path("user").path("authorities").values().stream()
                .map(role -> role.path("name").asString())
                .toList();
    }

    static MockHttpServletRequest request() {
        final var request = new MockHttpServletRequest();
        request.setRemoteAddr(CLIENT_IP);

        return request;
    }

    static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
