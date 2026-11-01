package cc.hrva.urlshortener.validator;

import java.time.LocalDateTime;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;

public interface ApiKeyValidator {

    void apiKeySlotsAvailable(User user);
    void verifyUserAdminOrOwner(ApiKey apiKey);
    void apiKeyExistsByKeyAndIsValid(String key);
    void validateExpiry(Integer expiresInDays, Boolean neverExpires, LocalDateTime expirationDate);

}
