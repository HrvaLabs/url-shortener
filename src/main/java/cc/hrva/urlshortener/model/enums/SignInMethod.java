package cc.hrva.urlshortener.model.enums;

import java.util.Locale;
import java.util.Optional;

public enum SignInMethod {

    PASSWORD,
    PASSKEY,
    GOOGLE,
    GITHUB;

    public static Optional<SignInMethod> ofProvider(final String provider) {
        if (provider == null) {
            return Optional.empty();
        }

        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "google" -> Optional.of(GOOGLE);
            case "github" -> Optional.of(GITHUB);
            default -> Optional.empty();
        };
    }

}
