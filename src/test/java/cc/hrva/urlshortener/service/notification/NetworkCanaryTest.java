package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.client.Resolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkCanaryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T01:00:00Z"));
    private final List<String> lookups = new CopyOnWriteArrayList<>();

    @Test
    void bothKnownHostsResolvingIsHealthy() {
        assertThat(new NetworkCanary(resolving(Set.of("example.com", "www.iana.org")), clock).healthy()).isTrue();
        assertThat(lookups).containsExactly("example.com");
    }

    @Test
    void oneKnownHostResolvingIsHealthy() {
        assertThat(new NetworkCanary(resolving(Set.of("www.iana.org")), clock).healthy()).isTrue();
        assertThat(lookups).containsExactly("example.com", "www.iana.org");
    }

    @Test
    void neitherResolvingIsUnhealthy() {
        assertThat(new NetworkCanary(resolving(Set.of()), clock).healthy()).isFalse();
        assertThat(lookups).containsExactly("example.com", "www.iana.org");
    }

    @Test
    void theAnswerIsKeptForAMinute() {
        final var canary = new NetworkCanary(resolving(Set.of()), clock);

        canary.healthy();
        clock.advance(Duration.ofSeconds(59));
        canary.healthy();
        assertThat(lookups).hasSize(2);

        clock.advance(Duration.ofSeconds(1));
        canary.healthy();
        assertThat(lookups).hasSize(4);
    }

    private Resolver resolving(final Set<String> known) {
        return host -> {
            lookups.add(host);
            if (!known.contains(host)) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 215, 14})};
        };
    }

}
