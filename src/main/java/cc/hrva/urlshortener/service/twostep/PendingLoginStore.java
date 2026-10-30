package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.Duration;
import java.util.Optional;

public interface PendingLoginStore {

    Duration TIME_TO_LIVE = Duration.ofMinutes(5);
    int MAX_ATTEMPTS = 5;
    String UNAVAILABLE = "Two-step sign-in isn't available right now. Try again in a minute.";

    String create(Long userId, boolean rememberMe, SignInMethod method);
    Optional<PendingLogin> find(String token);
    Optional<PendingLogin> consume(String token);
    void discard(String token);

}
