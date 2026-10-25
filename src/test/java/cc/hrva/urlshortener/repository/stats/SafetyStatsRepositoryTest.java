package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.model.Url;
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

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = SafetyStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:safety-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class SafetyStatsRepositoryTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 19, 22, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 26, 23, 0);

    @Autowired
    private SafetyStatsRepository safetyStatsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void resolvedInTheWindowByResolution() {
        resolved(9101, START.minusHours(3), START, "OK");
        resolved(9102, START.plusDays(1), START.plusDays(2), "OK");
        resolved(9103, START.plusDays(2), START.plusDays(3), "OK");
        resolved(9104, START.plusDays(3), END.minusSeconds(1), "LINK_BLOCKED");
        resolved(9105, START.plusDays(3), START.plusDays(4), "DOMAIN_TRUSTED");
        resolved(9106, START.minusDays(3), START.minusSeconds(1), "OK");
        resolved(9107, START.plusDays(5), END, "USER_BLOCKED");
        open(9108, START.plusDays(1));

        assertThat(safetyStatsRepository.countResolvedByResolution(START, END)).containsExactly(
                new CountEntry("OK", 3),
                new CountEntry("DOMAIN_TRUSTED", 1),
                new CountEntry("LINK_BLOCKED", 1));
    }

    @Test
    void resolveMinutesAreSortedDurations() {
        final var day = START.toLocalDate().plusDays(2);
        resolved(9201, day.atTime(10, 0), day.atTime(13, 30), "OK");
        resolved(9202, day.atTime(9, 0), day.plusDays(1).atTime(9, 0), "LINK_BLOCKED");
        resolved(9203, day.atTime(11, 0), day.atTime(11, 5, 59), "OK");
        resolved(9204, START.minusDays(1), START.minusMinutes(1), "OK");
        open(9205, day.atTime(8, 0));

        assertThat(safetyStatsRepository.findResolveMinutes(START, END)).containsExactly(5L, 210L, 1440L);
    }

    @Test
    void aResolvedReviewWithoutAResolutionIsUnknown() {
        resolved(9301, START.plusDays(1), START.plusDays(2), "OK");
        resolved(9302, START.plusDays(1), START.plusDays(2), null);
        resolved(9303, START.plusDays(2), START.plusDays(3), null);
        resolved(9304, START.minusDays(2), START.minusDays(1), null);

        assertThat(safetyStatsRepository.countResolvedByResolution(START, END)).containsExactly(
                new CountEntry("unknown", 2),
                new CountEntry("OK", 1));
    }

    @Test
    void aReviewResolvedBeforeItWasFlaggedTakesNoMinutes() {
        final var day = START.toLocalDate().plusDays(2);
        resolved(9401, day.atTime(12, 0), day.atTime(11, 0), "OK");
        resolved(9402, day.atTime(10, 0), day.atTime(10, 30), "OK");

        assertThat(safetyStatsRepository.findResolveMinutes(START, END)).containsExactly(0L, 30L);
    }

    @Test
    void anEmptySiteHasNoResolvedReviews() {
        assertThat(safetyStatsRepository.countResolvedByResolution(START, END)).isEmpty();
        assertThat(safetyStatsRepository.findResolveMinutes(START, END)).isEmpty();
    }

    private void resolved(final long id, final LocalDateTime flaggedAt, final LocalDateTime resolvedAt, final String resolution) {
        link(id);
        jdbcTemplate.update("insert into link_review (id, url_id, reasons, flagged_at, resolved_at, resolved_by, resolution) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                id, id, "destination host is a raw IP address", flaggedAt, resolvedAt, "admin@hrva.cc", resolution);
    }

    private void open(final long id, final LocalDateTime flaggedAt) {
        link(id);
        jdbcTemplate.update("insert into link_review (id, url_id, reasons, flagged_at) values (?, ?, ?, ?)",
                id, id, "reported by a visitor: phishing", flaggedAt);
    }

    private void link(final long id) {
        jdbcTemplate.update("insert into url (id, long_url, short_url) values (?, ?, ?)", id, "https://example.org/" + id, "safety-" + id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import(SafetyStatsRepository.class)
    static class TestApplication {
    }

}
