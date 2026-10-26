package cc.hrva.urlshortener.mcp.anon;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

public interface RateLimiter {

    long increment(String key, Duration window);

    default boolean tryAcquire(final String key, final int limit, final Duration window) {
        return increment(key, window) <= limit;
    }

    static String clientKey(final String name, final String clientIp) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(networkOf(clientIp).getBytes(StandardCharsets.UTF_8));
            return name + ":" + HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String networkOf(final String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "";
        }

        final InetAddress address;
        try {
            address = InetAddress.ofLiteral(clientIp.strip());
        } catch (final IllegalArgumentException exception) {
            return clientIp;
        }

        if (address instanceof Inet6Address) {
            return slash64Of(address);
        }

        return address.getHostAddress();
    }

    private static String slash64Of(final InetAddress address) {
        final var bytes = address.getAddress();
        Arrays.fill(bytes, 8, bytes.length, (byte) 0);
        try {
            return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
        } catch (final UnknownHostException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
