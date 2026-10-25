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

import static cc.hrva.urlshortener.repository.stats.HourlySeries.LINKS_BY_CHANNEL;
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

    private void link(final long id, final LocalDateTime createDate, final String createdVia) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, create_date, created_via) values (?, ?, ?, ?, ?)",
                id, "https://example.org/" + id, "hourly-" + id, createDate, createdVia);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import(HourlyCountRepository.class)
    static class TestApplication {
    }

}
