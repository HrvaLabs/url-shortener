package cc.hrva.urlshortener.validator;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiKeyValidatorTest {

    private ApiKeyValidator apiKeyValidator;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private UserService userService;

    private ApiKey apiKey;

    @BeforeEach
    void setUp() {
        this.apiKeyValidator = new DefaultApiKeyValidator(
                this.userService,
                this.apiKeyRepository,
                java.time.Clock.systemUTC()
        );

        this.apiKey = ApiKey.builder()
                .keyHash(ApiKeyTokens.hash("test"))
                .apiCallsUsed(0L)
                .createDate(LocalDateTime.now())
                .expirationDate(LocalDateTime.now().plusDays(1L))
                .active(true)
                .owner(User.builder().id(1L).active(true).build())
                .build();
    }

    @Test
    void anAdminWithoutTwoStepIsToldToTurnItOnForSomeoneElsesKey() {
        final User pending = User.builder().id(2L).twoStepEnabled(false)
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_ADMIN").build())).build();
        when(userService.getUserFromToken()).thenReturn(pending);

        assertThatThrownBy(() -> apiKeyValidator.verifyUserAdminOrOwner(apiKey))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Turn on two-step sign-in to use admin pages.");
    }

    @Test
    void shouldVerifyUserAdminOrOwner() {
        final Authorities authorities = Authorities.builder().id(1L).name("ROLE_ADMIN").build();
        final User user = User.builder().id(1L).authorities(Collections.singletonList(authorities)).build();
        apiKey.setOwner(user);

        when(userService.getUserFromToken()).thenReturn(user);

        assertThatCode(() -> apiKeyValidator.verifyUserAdminOrOwner(apiKey))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailVerifyUserAdminOrOwner() {
        final Authorities authorities = Authorities.builder().id(1L).name("ROLE_USER").build();
        final User user = User.builder().id(1L).authorities(Collections.singletonList(authorities)).build();
        apiKey.setOwner(User.builder().id(2L).build());

        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> apiKeyValidator.verifyUserAdminOrOwner(apiKey))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("You don't have authorization for this action");
    }

    @Test
    void theOwnerIsRecognisedByIdEvenAsAnotherInstance() {
        final Authorities authorities = Authorities.builder().id(1L).name("ROLE_USER").build();
        final User user = User.builder().id(7L).authorities(Collections.singletonList(authorities)).build();
        apiKey.setOwner(User.builder().id(7L).build());

        when(userService.getUserFromToken()).thenReturn(user);

        assertThatCode(() -> apiKeyValidator.verifyUserAdminOrOwner(apiKey))
                .doesNotThrowAnyException();
    }

    @Test
    void aUserWithoutAnIdIsNeverTheOwnerOfAKeyWithoutOne() {
        final Authorities authorities = Authorities.builder().id(1L).name("ROLE_USER").build();
        final User user = User.builder().authorities(Collections.singletonList(authorities)).build();
        apiKey.setOwner(User.builder().build());

        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> apiKeyValidator.verifyUserAdminOrOwner(apiKey))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("You don't have authorization for this action");
    }

    @Test
    void aKeyWithoutAnOwnerIsRefusedToAUser() {
        final Authorities authorities = Authorities.builder().id(1L).name("ROLE_USER").build();
        final User user = User.builder().id(7L).authorities(Collections.singletonList(authorities)).build();
        apiKey.setOwner(null);

        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> apiKeyValidator.verifyUserAdminOrOwner(apiKey))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("You don't have authorization for this action");
    }

    @Test
    void shouldApiKeyExistsByKeyAndIsValid() {
        final String key = "test";

        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailApiKeyExistsByKeyAndIsValidApiKeyDoesntExist() {
        final String key = "test";

        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.empty());

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage("API key doesn't exist");
    }

    @Test
    void aKeyWithManyCallsIsStillValid() {
        final String key = "test";

        apiKey.setApiCallsUsed(1_000_000L);
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .doesNotThrowAnyException();
    }

    @Test
    void aKeyWithoutAnExpiryIsValid() {
        final String key = "test";

        apiKey.setExpirationDate(null);
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .doesNotThrowAnyException();
    }

    @Test
    void aKeyWorksUntilTheSecondItExpiresOnTheValidatorsClock() {
        final var expiry = LocalDateTime.of(2026, 10, 31, 23, 50);
        apiKey.setExpirationDate(expiry);
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash("test"))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> validatorAt("2026-10-31T23:50:00Z").apiKeyExistsByKeyAndIsValid("test")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validatorAt("2026-10-31T23:50:01Z").apiKeyExistsByKeyAndIsValid("test"))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key has expired");
    }

    @Test
    void anExpirationDateLaterTodayIsInTheFutureOnTheValidatorsClock() {
        assertThatCode(() -> validatorAt("2026-10-30T10:00:00Z").validateExpiry(null, null, LocalDateTime.of(2026, 10, 30, 18, 0)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validatorAt("2026-10-30T18:00:00Z").validateExpiry(null, null, LocalDateTime.of(2026, 10, 30, 18, 0)))
                .isInstanceOf(FieldValidationException.class);
    }

    private ApiKeyValidator validatorAt(final String instant) {
        return new DefaultApiKeyValidator(userService, apiKeyRepository,
                java.time.Clock.fixed(java.time.Instant.parse(instant), java.time.ZoneOffset.UTC));
    }

    @Test
    void oneExpiryChoiceOrNoneIsAccepted() {
        assertThatCode(() -> apiKeyValidator.validateExpiry(null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> apiKeyValidator.validateExpiry(30, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> apiKeyValidator.validateExpiry(null, true, null)).doesNotThrowAnyException();
        assertThatCode(() -> apiKeyValidator.validateExpiry(30, false, null)).doesNotThrowAnyException();
        assertThatCode(() -> apiKeyValidator.validateExpiry(null, false, LocalDateTime.now().plusDays(2))).doesNotThrowAnyException();
    }

    @Test
    void anExpiryInDaysAndNoExpiryTogetherAreRefused() {
        assertThatThrownBy(() -> apiKeyValidator.validateExpiry(30, true, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Choose either an expiry in days or no expiry.");
    }

    @Test
    void anExpirationDateWithAnotherChoiceIsRefused() {
        final var later = LocalDateTime.now().plusDays(2);

        assertThatThrownBy(() -> apiKeyValidator.validateExpiry(30, null, later))
                .isInstanceOf(ApiException.class)
                .hasMessage("Choose either an expiry in days or no expiry.");
        assertThatThrownBy(() -> apiKeyValidator.validateExpiry(null, true, later))
                .isInstanceOf(ApiException.class)
                .hasMessage("Choose either an expiry in days or no expiry.");
    }

    @Test
    void anExpirationDateInThePastIsRefusedOnItsField() {
        assertThatThrownBy(() -> apiKeyValidator.validateExpiry(null, null, LocalDateTime.now().minusMinutes(1)))
                .isInstanceOfSatisfying(FieldValidationException.class, exception -> org.assertj.core.api.Assertions.assertThat(exception.getFieldErrors())
                        .containsExactly(java.util.Map.entry("expirationDate", "Choose a date in the future.")));
    }

    @Test
    void shouldFailApiKeyExistsByKeyAndIsValidExceededExpirationDate() {
        final String key = "test";

        apiKey.setExpirationDate(LocalDateTime.now().minusDays(1));
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key has expired");
    }

    @Test
    void shouldFailApiKeyExistsByKeyAndIsValidApiNotValid() {
        final String key = "test";

        apiKey.setActive(false);
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key is invalid");
    }

    @Test
    void shouldFailApiKeyExistsByKeyAndIsValidWhenOwnerInactive() {
        final String key = "test";

        apiKey.setOwner(User.builder().id(1L).active(false).build());
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key owner account is inactive");
    }

    @Test
    void shouldFailApiKeyExistsByKeyAndIsValidWhenOwnerMissing() {
        final String key = "test";

        apiKey.setOwner(null);
        when(apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(key))).thenReturn(Optional.ofNullable(apiKey));

        assertThatCode(() -> apiKeyValidator.apiKeyExistsByKeyAndIsValid(key))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key owner account is inactive");
    }
}