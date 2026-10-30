package cc.hrva.urlshortener.model.enums;

import java.util.Locale;

public enum SignInMethod {

    PASSWORD,
    PASSKEY,
    GOOGLE,
    GITHUB;

    public static SignInMethod ofProvider(final String provider) {
        if (provider == null) {
            return PASSWORD;
        }

        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "google" -> GOOGLE;
            case "github" -> GITHUB;
            default -> PASSWORD;
        };
    }

}
