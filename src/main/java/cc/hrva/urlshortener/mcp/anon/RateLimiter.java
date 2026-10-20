package cc.hrva.urlshortener.mcp.anon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public interface RateLimiter {

    boolean tryAcquire(String key, int limit, Duration window);

    static String clientKey(final String name, final String clientIp) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(clientIp.getBytes(StandardCharsets.UTF_8));
            return name + ":" + HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
