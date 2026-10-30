package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.LocalDateTime;

public record SignInMethodChangedEvent(User user, String change, LocalDateTime changedAt) {

    public static SignInMethodChangedEvent turnedOff(final User user, final SignInMethod provider, final LocalDateTime changedAt) {
        return new SignInMethodChangedEvent(user, nameOf(provider) + " sign-in was turned off.", changedAt);
    }

    public static SignInMethodChangedEvent turnedBackOn(final User user, final SignInMethod provider, final LocalDateTime changedAt) {
        return new SignInMethodChangedEvent(user, nameOf(provider) + " sign-in was turned back on.", changedAt);
    }

    public static SignInMethodChangedEvent passwordRemoved(final User user, final LocalDateTime changedAt) {
        return new SignInMethodChangedEvent(user, "Your password was removed.", changedAt);
    }

    public static String nameOf(final SignInMethod provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case GITHUB -> "GitHub";
            case PASSKEY -> "Passkey";
            case PASSWORD -> "Email and password";
        };
    }

}
