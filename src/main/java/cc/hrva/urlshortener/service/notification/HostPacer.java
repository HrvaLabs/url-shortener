package cc.hrva.urlshortener.service.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HostPacer {

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration interval;
    private final Map<String, Instant> nextTurns = new HashMap<>();

    public HostPacer(final Clock clock, final Sleeper sleeper, final Duration interval) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.interval = interval;
    }

    public void awaitTurn(final String host) {
        final var wait = reserveTurn(host.toLowerCase(Locale.ROOT));
        if (!wait.isPositive()) {
            return;
        }
        try {
            sleeper.sleep(wait);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized Duration reserveTurn(final String host) {
        final var now = clock.instant();
        final var next = nextTurns.get(host);
        final var turn = next != null && next.isAfter(now) ? next : now;
        nextTurns.put(host, turn.plus(interval));
        return Duration.between(now, turn);
    }

    @FunctionalInterface
    public interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;

    }

}
