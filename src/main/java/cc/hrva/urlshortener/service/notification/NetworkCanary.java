package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.client.Resolver;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class NetworkCanary {

    private static final List<String> KNOWN_HOSTS = List.of("example.com", "www.iana.org");
    private static final Duration KEPT_FOR = Duration.ofMinutes(1);

    private final Resolver resolver;
    private final Clock clock;
    private Instant checkedAt;
    private boolean healthy;

    public NetworkCanary(final Resolver resolver, final Clock clock) {
        this.resolver = resolver;
        this.clock = clock;
    }

    public synchronized boolean healthy() {
        final var now = clock.instant();
        if (checkedAt == null || !now.isBefore(checkedAt.plus(KEPT_FOR))) {
            healthy = KNOWN_HOSTS.stream().anyMatch(this::resolves);
            checkedAt = now;
        }
        return healthy;
    }

    private boolean resolves(final String host) {
        try {
            return resolver.resolve(host).length > 0;
        } catch (final UnknownHostException exception) {
            return false;
        }
    }

}
