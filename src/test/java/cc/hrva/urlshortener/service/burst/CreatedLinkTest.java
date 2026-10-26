package cc.hrva.urlshortener.service.burst;

import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreatedLinkTest {

    @Test
    void anAccountIsItsId() {
        assertThat(CreatedLink.creatorOf(7L, "203.0.113.7")).isEqualTo("user:7");
        assertThat(CreatedLink.creatorOf(7L, null)).isEqualTo("user:7");
    }

    @Test
    void anonymousCreatorsAreTheirHashedNetwork() {
        final var creator = CreatedLink.creatorOf(null, "203.0.113.7");

        assertThat(creator).matches("anonymous:[0-9a-f]{64}").doesNotContain("203.0.113");
        assertThat(creator).isEqualTo(RateLimiter.clientKey("anonymous", "203.0.113.7"));
        assertThat(CreatedLink.creatorOf(null, "203.0.113.8")).isNotEqualTo(creator);
    }

    @Test
    void twoIpv6AddressesInOneSlash64AreOneCreator() {
        final var creator = CreatedLink.creatorOf(null, "2001:db8:1:2::1");

        assertThat(CreatedLink.creatorOf(null, "2001:db8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(creator);
        assertThat(CreatedLink.creatorOf(null, "2001:db8:1:3::1")).isNotEqualTo(creator);
        assertThat(creator).doesNotContain("2001");
    }

}
