package cc.hrva.urlshortener.security;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@RequiredArgsConstructor
public class BcryptPasswords implements PasswordEncoder {

    public static final int MAX_BYTES = 72;

    private final PasswordEncoder bcrypt;

    public static boolean tooLong(final CharSequence password) {
        return password.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    @Override
    public String encode(final CharSequence rawPassword) {
        return bcrypt.encode(rawPassword);
    }

    @Override
    public boolean matches(final CharSequence rawPassword, final String encodedPassword) {
        if (rawPassword != null && tooLong(rawPassword)) {
            return false;
        }

        return bcrypt.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(final String encodedPassword) {
        return bcrypt.upgradeEncoding(encodedPassword);
    }

}
