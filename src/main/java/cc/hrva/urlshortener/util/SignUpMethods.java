package cc.hrva.urlshortener.util;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public final class SignUpMethods {

    private static final String LOCAL_PROVIDER = "local";

    private SignUpMethods() {}

    public static String of(final String authProvider) {
        final var provider = StringUtils.defaultIfBlank(authProvider, LOCAL_PROVIDER).toLowerCase(Locale.ROOT);
        return switch (provider) {
            case LOCAL_PROVIDER -> "email";
            case "google" -> "Google";
            case "github" -> "GitHub";
            default -> provider;
        };
    }

}
