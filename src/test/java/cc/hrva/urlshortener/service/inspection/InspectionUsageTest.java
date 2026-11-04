package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = InspectionTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                InspectionTestApplication.DATABASE_URL,
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc",
                "app.mcp.signing-key=test-only"
        })
class InspectionUsageTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Autowired
    private InspectionUsage inspectionUsage;

    @Autowired
    private BrowserTimeUsed browserTimeUsed;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private InMemoryRedis inMemoryRedis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        inMemoryRedis.expireKeysContaining("");
        jdbcTemplate.update("delete from link_screenshot");
        jdbcTemplate.update("delete from link_inspection");
        jdbcTemplate.update("delete from url");
        appProperties.getInspection().setVirusTotalDailyLimit(3);
        for (var id = 9501L; id <= 9505L; id++) {
            jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits) values (?, ?, 'https://example.com/', true, 0)", id, "u" + id);
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from link_inspection");
        jdbcTemplate.update("delete from url");
        appProperties.getInspection().setVirusTotalDailyLimit(480);
    }

    @Test
    void countsOnlyThePendingScreenshotsAndLookups() {
        inspection(9501L, "PENDING", "PENDING");
        inspection(9502L, "PENDING", "DONE");
        inspection(9503L, "FAILED", "PENDING");
        inspection(9504L, "DONE", null);
        inspection(9505L, null, "PENDING");

        assertThat(inspectionUsage.screenshotsQueued()).hasValue(2);
        assertThat(inspectionUsage.virusTotalQueued()).hasValue(3);
    }

    @Test
    void nothingQueuedIsZero() {
        assertThat(inspectionUsage.screenshotsQueued()).hasValue(0);
        assertThat(inspectionUsage.virusTotalQueued()).hasValue(0);
    }

    @Test
    void todaysLookupsStopAtTheDailyLimit() {
        assertThat(inspectionUsage.virusTotalLookupsToday()).hasValue(0);

        rateLimiter.increment(VirusTotalQuota.keyOfToday(clock), VirusTotalQuota.KEPT_FOR);
        rateLimiter.increment(VirusTotalQuota.keyOfToday(clock), VirusTotalQuota.KEPT_FOR);
        assertThat(inspectionUsage.virusTotalLookupsToday()).hasValue(2);

        rateLimiter.increment(VirusTotalQuota.keyOfToday(clock), VirusTotalQuota.KEPT_FOR);
        rateLimiter.increment(VirusTotalQuota.keyOfToday(clock), VirusTotalQuota.KEPT_FOR);
        assertThat(inspectionUsage.virusTotalLookupsToday()).hasValue(3);
    }

    @Test
    void readsTodaysBrowserSeconds() {
        browserTimeUsed.add(42_000L);

        assertThat(inspectionUsage.browserSecondsToday()).hasValue(42.0);

        clock.advance(Duration.ofDays(1));
        assertThat(inspectionUsage.browserSecondsToday()).hasValue(0.0);
    }

    private void inspection(final long urlId, final String screenshotState, final String vtState) {
        jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, vt_state, vt_requested_at) values (?, ?, ?, ?, ?)",
                urlId, screenshotState, screenshotState == null ? null : NOW_UTC, vtState, vtState == null ? null : NOW_UTC);
    }

}
