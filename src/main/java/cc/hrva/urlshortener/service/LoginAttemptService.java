package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LoginAttemptService {

    String TWO_FACTOR_PREFIX = "2fa:";
    String NETWORK_BLOCKED = "Too many failed sign-ins from this network. Try again tomorrow.";
    String TWO_FACTOR_BLOCKED = "Too many wrong codes for this account. Try again in 24 hours.";
    String TWO_FACTOR_BLOCKED_OR_PASSKEY = "Too many wrong codes for this account. Try again in 24 hours, or sign in with a passkey.";

    void loginFailed(String key);
    boolean isBlocked(String key);
    void loginSucceeded(String key);
    Page<LoginAttemptResponse> attempts(String search, Pageable pageable);
    void clearLoginAttempts();

}
