package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.ApiKeySlotException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultApiKeyValidator implements ApiKeyValidator {

    private static final String CHOOSE_ONE_EXPIRY = "Choose either an expiry in days or no expiry.";
    private static final String EXPIRATION_DATE = "expirationDate";
    private static final String EXPIRATION_DATE_IN_THE_PAST = "Choose a date in the future.";

    private final UserService userService;
    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    @Override
    public void apiKeyExistsByKeyAndIsValid(final String key) {
        final var hash = ApiKeyTokens.hash(key);
        final var apiKey = apiKeyRepository.findApiKeyByKeyHash(hash)
                .filter(found -> ApiKeyTokens.sameHash(found.getKeyHash(), hash))
                .orElseThrow(() -> new ApiKeyNotFoundException("API key doesn't exist"));

        if (apiKey.getExpirationDate() != null && LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).isAfter(apiKey.getExpirationDate())) {
            throw new InvalidApiKeyException("API key has expired");
        }

        if (!apiKey.isActive()) {
            throw new InvalidApiKeyException("API key is invalid");
        }

        if (apiKey.getOwner() == null || Boolean.FALSE.equals(apiKey.getOwner().getActive())) {
            throw new InvalidApiKeyException("API key owner account is inactive");
        }
    }

    @Override
    public void validateExpiry(final Integer expiresInDays, final Boolean neverExpires, final LocalDateTime expirationDate) {
        final var choices = Stream.of(expiresInDays != null, Boolean.TRUE.equals(neverExpires), expirationDate != null)
                .filter(Boolean::booleanValue)
                .count();
        if (choices > 1) {
            throw new ApiException(CHOOSE_ONE_EXPIRY);
        }
        if (expirationDate != null && !expirationDate.isAfter(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())))) {
            throw new FieldValidationException(EXPIRATION_DATE, EXPIRATION_DATE_IN_THE_PAST);
        }
    }

    @Override
    public void verifyUserAdminOrOwner(final ApiKey apiKey) {
        final var currentUser = userService.getUserFromToken();
        final boolean isCurrentUserAdmin = currentUser.getAuthorities().stream()
                .anyMatch(authorities -> authorities.getName().equals("ROLE_ADMIN"));
        final var owner = apiKey.getOwner();
        final var isOwner = owner != null && currentUser.getId() != null && currentUser.getId().equals(owner.getId());
        if (!isOwner && !isCurrentUserAdmin) {
            throw new NoAuthorizationException("You don't have authorization for this action");
        }
    }

    @Override
    public void apiKeySlotsAvailable(final User user) {
        if (user.getApiKeySlots() <= apiKeyRepository.findByOwnerAndActiveTrue(user).size()) {
            throw new ApiKeySlotException("API key slot limit reached. Contact admin to increase your limit.");
        }
    }

}
