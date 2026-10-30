package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.model.Url;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static cc.hrva.urlshortener.repository.stats.HourlySeries.BLOCKS_BY_REASON;
import static cc.hrva.urlshortener.repository.stats.HourlySeries.EMAILS_BY_STATUS;
import static cc.hrva.urlshortener.repository.stats.HourlySeries.LINKS_BY_CHANNEL;
import static cc.hrva.urlshortener.repository.stats.HourlySeries.REVIEWS_BY_SOURCE;
import static cc.hrva.urlshortener.repository.stats.HourlySeries.SIGNUPS_BY_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = HourlyCountRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:hourly-count;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class HourlyCountRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @Autowired
    private HourlyCountRepository hourlyCountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countsLinksPerStoredHourAndChannel() {
        link(9001, DAY.atTime(21, 30), "WEB");
        link(9002, DAY.atTime(22, 15), "WEB");
        link(9003, DAY.atTime(22, 45), null);
        link(9004, DAY.atTime(23, 5), "MCP");
        link(9005, DAY.atTime(23, 40), "MCP");

        assertThat(hourlyCountRepository.count(LINKS_BY_CHANNEL, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .containsExactlyInAnyOrder(
                        new HourlyCount(DAY, 21, "WEB", 1),
                        new HourlyCount(DAY, 22, "WEB", 1),
                        new HourlyCount(DAY, 22, "unknown", 1),
                        new HourlyCount(DAY, 23, "MCP", 2));
        assertThat(hourlyCountRepository.count(LINKS_BY_CHANNEL, DAY.plusDays(1).atStartOfDay(), DAY.plusDays(2).atStartOfDay()))
                .isEmpty();
    }

    @Test
    void theWindowEndIsExclusive() {
        final var start = DAY.atTime(22, 0);
        final var end = DAY.atTime(23, 0);
        link(9011, start.minusSeconds(1), "API_KEY");
        link(9012, start, "API_KEY");
        link(9013, end, "API_KEY");

        assertThat(hourlyCountRepository.count(LINKS_BY_CHANNEL, start, end))
                .containsExactly(new HourlyCount(DAY, 22, "API_KEY", 1));
    }

    @Test
    void countsSignUpsPerHourAndProvider() {
        account(9101, DAY.atTime(8, 10), "local");
        accountWithoutProvider(9102, DAY.atTime(8, 50));
        account(9103, DAY.atTime(8, 55), "google");
        account(9104, DAY.atTime(14, 20), "github");
        account(9105, DAY.atTime(14, 40), "github");
        account(9106, DAY.plusDays(1).atTime(0, 5), "google");

        assertThat(hourlyCountRepository.count(SIGNUPS_BY_PROVIDER, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .containsExactlyInAnyOrder(
                        new HourlyCount(DAY, 8, "local", 2),
                        new HourlyCount(DAY, 8, "google", 1),
                        new HourlyCount(DAY, 14, "github", 2));
    }

    @Test
    void countsReviewsBySource() {
        review(9201, DAY.atTime(9, 5), true);
        review(9202, DAY.atTime(9, 40), true);
        review(9203, DAY.atTime(9, 50), false);
        review(9204, DAY.atTime(17, 15), false);
        review(9205, DAY.minusDays(1).atTime(23, 59), true);

        assertThat(hourlyCountRepository.count(REVIEWS_BY_SOURCE, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .containsExactlyInAnyOrder(
                        new HourlyCount(DAY, 9, "SYSTEM", 2),
                        new HourlyCount(DAY, 9, "VISITOR", 1),
                        new HourlyCount(DAY, 17, "VISITOR", 1));
    }

    @Test
    void countsBlocksByReasonAndSkipsUnblockedLinks() {
        blockedLink(9301, DAY.atTime(8, 10), "BLOCKLIST");
        blockedLink(9302, DAY.atTime(8, 45), "BLOCKLIST");
        blockedLink(9303, DAY.atTime(8, 50), "SCAM_SIGNALS");
        blockedLink(9304, DAY.atTime(20, 0), "ADMIN");
        blockedLink(9305, DAY.atTime(20, 30), null);
        blockedLink(9306, null, "SAFE_BROWSING");
        blockedLink(9308, DAY.atTime(20, 40), "CAMPAIGN");
        link(9307, DAY.atTime(8, 20), "WEB");

        assertThat(hourlyCountRepository.count(BLOCKS_BY_REASON, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .containsExactlyInAnyOrder(
                        new HourlyCount(DAY, 8, "BLOCKLIST", 2),
                        new HourlyCount(DAY, 8, "SCAM_SIGNALS", 1),
                        new HourlyCount(DAY, 20, "ADMIN", 1),
                        new HourlyCount(DAY, 20, "CAMPAIGN", 1),
                        new HourlyCount(DAY, 20, "unknown", 1));
    }

    @Test
    void countsEmailsPerHourAndStatus() {
        email(9401, DAY.atTime(7, 10), "SENT");
        email(9402, DAY.atTime(7, 40), "SENT");
        email(9403, DAY.atTime(7, 55), "FAILED");
        email(9404, DAY.atTime(19, 5), "RESENT");
        email(9405, DAY.atTime(19, 30), "SENDING");
        email(9406, DAY.plusDays(1).atStartOfDay(), "SENT");
        email(9407, DAY.minusDays(1).atTime(23, 59), "FAILED");

        assertThat(hourlyCountRepository.count(EMAILS_BY_STATUS, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .containsExactlyInAnyOrder(
                        new HourlyCount(DAY, 7, "SENT", 2),
                        new HourlyCount(DAY, 7, "FAILED", 1),
                        new HourlyCount(DAY, 19, "RESENT", 1),
                        new HourlyCount(DAY, 19, "SENDING", 1));
    }

    private void link(final long id, final LocalDateTime createDate, final String createdVia) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, create_date, created_via) values (?, ?, ?, ?, ?)",
                id, "https://example.org/" + id, "hourly-" + id, createDate, createdVia);
    }

    private void review(final long id, final LocalDateTime flaggedAt, final boolean systemFlagged) {
        link(id, flaggedAt, "WEB");
        jdbcTemplate.update("insert into link_review (id, url_id, reasons, flagged_at, system_flagged) values (?, ?, ?, ?, ?)",
                id, id, systemFlagged ? "destination host is a raw IP address" : "reported by a visitor: phishing", flaggedAt, systemFlagged);
    }

    private void blockedLink(final long id, final LocalDateTime blockedAt, final String blockReason) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, active, block_reason, blocked_at) values (?, ?, ?, false, ?, ?)",
                id, "https://example.org/" + id, "hourly-" + id, blockReason, blockedAt);
    }

    private void account(final long id, final LocalDateTime createDate, final String authProvider) {
        jdbcTemplate.update("insert into user_account (id, email, password, create_date, auth_provider) values (?, ?, ?, ?, ?)",
                id, "signup-" + id + "@example.com", "correct horse battery", createDate, authProvider);
    }

    private void accountWithoutProvider(final long id, final LocalDateTime createDate) {
        jdbcTemplate.update("insert into user_account (id, email, password, create_date) values (?, ?, ?, ?)",
                id, "signup-" + id + "@example.com", "correct horse battery", createDate);
    }

    private void email(final long id, final LocalDateTime createdAt, final String status) {
        jdbcTemplate.update("insert into email_log (id, recipient, subject, status, created_at) values (?, ?, ?, ?, ?)",
                id, "reader-" + id + "@example.com", "Verify your email", status, createdAt);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import(HourlyCountRepository.class)
    static class TestApplication {
    }

}
