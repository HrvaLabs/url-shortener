package cc.hrva.urlshortener.service.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostPacerTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), ZoneOffset.UTC);

    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final HostPacer pacer = new HostPacer(CLOCK, sleeps::add, INTERVAL);

    @Test
    void sameHostWaitsFiveSeconds() {
        pacer.awaitTurn("example.com");
        pacer.awaitTurn("example.com");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void aThirdRequestWaitsForTheSecondsTurnToo() {
        pacer.awaitTurn("example.com");
        pacer.awaitTurn("example.com");
        pacer.awaitTurn("example.com");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void otherHostsDoNotWait() {
        pacer.awaitTurn("example.com");
        pacer.awaitTurn("example.org");
        pacer.awaitTurn("www.example.com");

        assertThat(sleeps).isEmpty();
    }

    @Test
    void hostsAreCaseInsensitive() {
        pacer.awaitTurn("Example.COM");
        pacer.awaitTurn("example.com");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void aTurnThatHasPassedNeedsNoWait() {
        final var clock = new MutableClock(Instant.parse("2026-10-27T01:00:00Z"));
        final var moving = new HostPacer(clock, sleeps::add, INTERVAL);

        moving.awaitTurn("example.com");
        clock.advance(Duration.ofSeconds(2));
        moving.awaitTurn("example.com");
        clock.advance(Duration.ofSeconds(9));
        moving.awaitTurn("example.com");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void concurrentCallersEachGetTheirOwnTurn() throws InterruptedException {
        final var executor = Executors.newFixedThreadPool(4);
        for (int caller = 0; caller < 4; caller++) {
            executor.submit(() -> pacer.awaitTurn("example.com"));
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(sleeps).containsExactlyInAnyOrder(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    @Test
    void anInterruptedWaitKeepsTheInterruptFlag() {
        final var interrupting = new HostPacer(CLOCK, duration -> {
            throw new InterruptedException();
        }, INTERVAL);

        interrupting.awaitTurn("example.com");
        interrupting.awaitTurn("example.com");

        assertThat(Thread.interrupted()).isTrue();
    }

}
