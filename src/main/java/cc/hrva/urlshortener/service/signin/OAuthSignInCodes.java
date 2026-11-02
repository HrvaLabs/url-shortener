package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.Duration;
import java.util.Optional;

public interface OAuthSignInCodes {

    Duration TIME_TO_LIVE = Duration.ofSeconds(60);
    String UNAVAILABLE = "Signing in isn't available right now. Try again in a minute.";

    String create(Long userId, SignInMethod method, String challenge);
    Optional<OAuthSignIn> consume(String code);

}
