package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.service.notification.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecentRequestsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T22:00:30Z"));
    private final RecentRequests recentRequests = new RecentRequests(clock);

    @Test
    void nothingRecordedIsAnEmptyHourWithoutPercentiles() {
        assertThat(recentRequests.window()).isEqualTo(new RecentRequests.Window(60, 0, 0, 0, null, null));
    }

    @Test
    void countsRequestsRedirectsAndServerErrors() {
        recentRequests.record("/abc", 302, 3);
        recentRequests.record("/abc", 301, 3);
        recentRequests.record("/api/v1/urls", 200, 3);
        recentRequests.record("/api/v1/urls", 404, 3);
        recentRequests.record("/api/v1/urls", 500, 3);
        recentRequests.record("/api/v1/urls", 503, 3);
        recentRequests.record("/api/v1/urls", 499, 3);

        final var window = recentRequests.window();

        assertThat(window.requests()).isEqualTo(7);
        assertThat(window.redirects()).isEqualTo(2);
        assertThat(window.serverErrors()).isEqualTo(2);
    }

    @Test
    void actuatorRequestsAreLeftOut() {
        recentRequests.record("/actuator/prometheus", 200, 900);
        recentRequests.record("/actuator/health", 503, 900);
        recentRequests.record("/actuator", 401, 900);
        recentRequests.record("/actuatorx", 302, 2);

        final var window = recentRequests.window();

        assertThat(window.requests()).isEqualTo(1);
        assertThat(window.redirects()).isEqualTo(1);
        assertThat(window.serverErrors()).isZero();
        assertThat(window.p95Ms()).isEqualTo(2.0);
    }

    @Test
    void theMedianAndTheSlowestFivePercentComeFromTheLatencyBuckets() {
        IntStream.range(0, 50).forEach(request -> recentRequests.record("/abc", 302, 4));
        IntStream.range(0, 45).forEach(request -> recentRequests.record("/abc", 302, 10));
        IntStream.range(0, 5).forEach(request -> recentRequests.record("/api/v1/urls", 200, 240));

        final var window = recentRequests.window();

        assertThat(window.p50Ms()).isEqualTo(4.0);
        assertThat(window.p95Ms()).isEqualTo(10.0);

        recentRequests.record("/api/v1/urls", 200, 240);
        assertThat(recentRequests.window().p95Ms()).isEqualTo(240.0);
    }

    @Test
    void aPercentileIsTheBucketsUpperBoundButNeverAboveTheSlowestRequest() {
        recentRequests.record("/abc", 302, 0.4);
        recentRequests.record("/abc", 302, 0.6);

        assertThat(recentRequests.window().p50Ms()).isEqualTo(0.6);

        recentRequests.record("/abc", 302, 33);
        recentRequests.record("/abc", 302, 34);
        assertThat(recentRequests.window().p95Ms()).isEqualTo(34.0);
        assertThat(recentRequests.window().p50Ms()).isEqualTo(1.0);
    }

    @Test
    void aRequestSlowerThanTheLastBucketReportsTheSlowest() {
        recentRequests.record("/abc", 504, 75_000);

        assertThat(recentRequests.window().p50Ms()).isEqualTo(75_000.0);
        assertThat(recentRequests.window().p95Ms()).isEqualTo(75_000.0);
    }

    @Test
    void theWindowKeepsSixtyMinutesAndOlderBucketsExpire() {
        recentRequests.record("/abc", 500, 900);
        clock.advance(Duration.ofMinutes(30));
        recentRequests.record("/abc", 302, 5);
        clock.advance(Duration.ofMinutes(29));

        assertThat(recentRequests.window().requests()).isEqualTo(2);
        assertThat(recentRequests.window().serverErrors()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));

        final var window = recentRequests.window();
        assertThat(window.requests()).isEqualTo(1);
        assertThat(window.serverErrors()).isZero();
        assertThat(window.p95Ms()).isEqualTo(5.0);

        clock.advance(Duration.ofMinutes(30));
        assertThat(recentRequests.window()).isEqualTo(new RecentRequests.Window(60, 0, 0, 0, null, null));
    }

    @Test
    void aSlotReusedAnHourLaterStartsFresh() {
        recentRequests.record("/abc", 500, 900);
        clock.advance(Duration.ofMinutes(60));
        recentRequests.record("/abc", 302, 5);

        final var window = recentRequests.window();

        assertThat(window.requests()).isEqualTo(1);
        assertThat(window.serverErrors()).isZero();
        assertThat(window.p50Ms()).isEqualTo(5.0);
    }

    @Test
    void requestsFromManyThreadsAreAllCounted() {
        try (final var executor = Executors.newFixedThreadPool(8)) {
            IntStream.range(0, 8_000).forEach(request -> executor.submit(() -> recentRequests.record("/abc", 302, 2)));
        }

        assertThat(recentRequests.window().requests()).isEqualTo(8_000);
        assertThat(recentRequests.window().redirects()).isEqualTo(8_000);
    }

}
