package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.repository.ApiKeyRepository;
import java.time.LocalDateTime;
import java.util.Optional;
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

    private final UserService userService;
    private final ApiKeyRepository apiKeyRepository;

    @Override
    public void apiKeyExistsByKeyAndIsValid(final String key) {
        final var apiKey = apiKeyRepository.findApiKeyByKey(key)
                .orElseThrow(() -> new ApiKeyNotFoundException("API key doesn't exist"));

        if (apiKey.getApiCallsUsed() >= Optional.ofNullable(apiKey.getApiCallsLimit())
                .orElse(apiKey.getApiCallsUsed()) + 1) {
            throw new InvalidApiKeyException("API key call limit reached");
        }

        if (LocalDateTime.now().isAfter(Optional.ofNullable(apiKey.getExpirationDate())
                .orElse(LocalDateTime.now().plusDays(1)))) {
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
