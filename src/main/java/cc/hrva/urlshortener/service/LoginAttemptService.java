package cc.hrva.urlshortener.service;

public interface LoginAttemptService {

    String TWO_FACTOR_PREFIX = "2fa:";
    String NETWORK_BLOCKED = "Request has been blocked";
    String TWO_FACTOR_BLOCKED = "Too many invalid two-factor codes. Try again later.";

    void loginFailed(String key);
    boolean isBlocked(String key);
    void loginSucceeded(String key);
    java.util.Map<String, Integer> getLoginAttempts();
    void clearLoginAttempts();

}
