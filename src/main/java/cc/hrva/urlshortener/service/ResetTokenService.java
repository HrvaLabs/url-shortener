package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.User;

public interface ResetTokenService {

    void deleteExpiredPasswordResetTokens();
    String createTokenForUser(User user);
    void deactivateExpiredPasswordResetTokens();
    void deactivateActiveResetTokenIfExists(User user);
    ResetToken claim(String token);

}
