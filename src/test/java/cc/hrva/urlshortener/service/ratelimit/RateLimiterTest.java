package cc.hrva.urlshortener.service.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final String NAME = "dcr";
    private static final String KEY_PATTERN = "dcr:[0-9a-f]{64}";

    @Test
    void ipv4AddressIsHashedUnchanged() {
        final var key = RateLimiter.clientKey(NAME, "203.0.113.7");

        assertThat(key).isEqualTo(NAME + ":" + sha256("203.0.113.7")).doesNotContain("203.0.113.7");
        assertThat(RateLimiter.clientKey(NAME, "203.0.113.8")).isNotEqualTo(key);
    }

    @Test
    void ipv6AddressesInTheSameSlash64ShareAKey() {
        final var key = RateLimiter.clientKey(NAME, "2001:db8:1:2::1");

        assertThat(key).matches(KEY_PATTERN);
        assertThat(RateLimiter.clientKey(NAME, "2001:db8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(key);
        assertThat(RateLimiter.clientKey(NAME, "2001:DB8:1:2:0:0:0:abcd")).isEqualTo(key);
        assertThat(RateLimiter.clientKey(NAME, "2001:db8:1:3::1")).isNotEqualTo(key);
        assertThat(RateLimiter.clientKey(NAME, "2001:db8:1:2::1")).doesNotContain("2001");
    }

    @Test
    void ipv4MappedIpv6AddressSharesTheIpv4Key() {
        assertThat(RateLimiter.clientKey(NAME, "::ffff:203.0.113.7")).isEqualTo(RateLimiter.clientKey(NAME, "203.0.113.7"));
    }

    @Test
    void missingOrUnparseableAddressStillYieldsAKey() {
        assertThat(RateLimiter.clientKey(NAME, null)).matches(KEY_PATTERN).isEqualTo(RateLimiter.clientKey(NAME, ""));
        assertThat(RateLimiter.clientKey(NAME, "not-an-ip.example")).matches(KEY_PATTERN)
                .isEqualTo(NAME + ":" + sha256("not-an-ip.example"))
                .isNotEqualTo(RateLimiter.clientKey(NAME, ""));
    }

    @Test
    void keyIsScopedByName() {
        assertThat(RateLimiter.clientKey("report", "203.0.113.7")).isNotEqualTo(RateLimiter.clientKey(NAME, "203.0.113.7"));
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
