package cc.hrva.urlshortener.security;

import java.util.regex.Pattern;

public final class StoredPasswords {

    private static final Pattern BCRYPT_HASH = Pattern.compile("\\A\\$2[aby]?\\$\\d\\d\\$[./0-9A-Za-z]{53}\\z");

    private StoredPasswords() {}

    public static boolean isSet(final String stored) {
        return stored != null && BCRYPT_HASH.matcher(stored).matches();
    }

    public static String none() {
        return SecureTokens.random();
    }

}
