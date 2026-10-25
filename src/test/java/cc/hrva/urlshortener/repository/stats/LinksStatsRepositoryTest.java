package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository.CreatedCounts;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository.InventoryCounts;
import jakarta.persistence.EntityManager;
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
        classes = LinksStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:links-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class LinksStatsRepositoryTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 19, 22, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 26, 23, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 0);

    @Autowired
    private LinksStatsRepository linksStatsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void countsCreatedLinksInTheWindow() {
        final var owner = savedOwner("created@example.com");
        created(9101, START, null);
        created(9102, START.plusDays(2), owner);
        created(9103, END.minusSeconds(1), owner);
        tag(9102, "work");
        tag(9103, "ai");
        tag(9103, "work");
        created(9104, START.minusSeconds(1), null);
        tag(9104, "ai");
        created(9105, END, owner);
        tag(9105, "ai");

        assertThat(linksStatsRepository.countCreated(START, END)).isEqualTo(new CreatedCounts(3, 1, 1));
        assertThat(linksStatsRepository.countCreated(END.plusDays(1), END.plusDays(2))).isEqualTo(new CreatedCounts(0, 0, 0));
    }

    @Test
    void countsTheInventory() {
        inventory(9201, true, NOW.minusDays(1), null, null);
        inventory(9202, true, NOW.plusDays(3), "hash", null);
        inventory(9203, false, null, null, 5L);
        inventory(9204, true, null, null, 0L);
        inventory(9205, false, NOW.minusDays(9), "hash", 3L);
        inventory(9206, true, NOW, null, null);

        assertThat(linksStatsRepository.countInventory(NOW)).isEqualTo(new InventoryCounts(6, 4, 2, 2, 2, 4));
    }

    @Test
    void anEmptySiteCountsZero() {
        assertThat(linksStatsRepository.countCreated(START, END)).isEqualTo(new CreatedCounts(0, 0, 0));
        assertThat(linksStatsRepository.countInventory(NOW)).isEqualTo(new InventoryCounts(0, 0, 0, 0, 0, 0));
        assertThat(linksStatsRepository.countBlockedByReason()).isEmpty();
        assertThat(linksStatsRepository.findTopTags(10)).isEmpty();
        assertThat(linksStatsRepository.findTopDomains(10)).isEmpty();
    }

    @Test
    void blockedByReasonOrdersByCount() {
        blocked(9301, "SAFE_BROWSING");
        blocked(9302, "BLOCKLIST");
        blocked(9303, "ADMIN");
        blocked(9304, "BLOCKLIST");
        destination(9305, "https://example.org/fine", true);

        assertThat(linksStatsRepository.countBlockedByReason()).containsExactly(
                new CountEntry("BLOCKLIST", 2),
                new CountEntry("ADMIN", 1),
                new CountEntry("SAFE_BROWSING", 1));
    }

    @Test
    void topTagsOrderByCountThenTag() {
        destination(9401, "https://example.org/1", true);
        destination(9402, "https://example.org/2", true);
        destination(9403, "https://example.org/3", false);
        destination(9404, "https://example.org/4", true);
        tag(9401, "ai");
        tag(9401, "work");
        tag(9402, "ai");
        tag(9402, "news");
        tag(9403, "ai");
        tag(9403, "news");
        tag(9404, "work");
        tag(9404, "zeta");

        assertThat(linksStatsRepository.findTopTags(10)).containsExactly(
                new CountEntry("ai", 3),
                new CountEntry("news", 2),
                new CountEntry("work", 2),
                new CountEntry("zeta", 1));
        assertThat(linksStatsRepository.findTopTags(2)).containsExactly(
                new CountEntry("ai", 3),
                new CountEntry("news", 2));
    }

    @Test
    void topDomainsCountActiveLinksByHost() {
        destination(9501, "https://GitHub.com/a", true);
        destination(9502, "https://github.com/b", true);
        destination(9503, "https://x.com", false);
        destination(9504, "not a link", true);

        assertThat(linksStatsRepository.findTopDomains(10)).containsExactly(new CountEntry("github.com", 2));
    }

    @Test
    void topDomainsOrderByCountThenHostWithinTheLimit() {
        destination(9601, "https://b.example/1", true);
        destination(9602, "https://d.example/1", true);
        destination(9603, "https://a.example/1", true);
        destination(9604, "https://b.example/2", true);
        destination(9605, "https://c.example/1", true);
        destination(9606, "https://d.example/2", true);
        destination(9607, "https://d.example/3", true);

        assertThat(linksStatsRepository.findTopDomains(3)).containsExactly(
                new CountEntry("d.example", 3),
                new CountEntry("b.example", 2),
                new CountEntry("a.example", 1));
    }

    private void created(final long id, final LocalDateTime createDate, final User owner) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, create_date, owner_id) values (?, ?, ?, ?, ?)",
                id, "https://example.org/" + id, "links-" + id, createDate, owner != null ? owner.getId() : null);
    }

    private void inventory(final long id, final boolean active, final LocalDateTime expirationDate, final String passwordHash,
                           final Long visitLimit) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, active, expiration_date, password_hash, visit_limit) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                id, "https://example.org/" + id, "links-" + id, active, expirationDate, passwordHash, visitLimit);
    }

    private void blocked(final long id, final String blockReason) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, active, block_reason) values (?, ?, ?, false, ?)",
                id, "https://example.org/" + id, "links-" + id, blockReason);
    }

    private void destination(final long id, final String longUrl, final boolean active) {
        jdbcTemplate.update("insert into url (id, long_url, short_url, active) values (?, ?, ?, ?)",
                id, longUrl, "links-" + id, active);
    }

    private void tag(final long urlId, final String tag) {
        jdbcTemplate.update("insert into url_tags (url_id, tag) values (?, ?)", urlId, tag);
    }

    private User savedOwner(final String email) {
        final var owner = User.builder().email(email).password("correct horse battery").build();
        entityManager.persist(owner);
        entityManager.flush();
        return owner;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import(LinksStatsRepository.class)
    static class TestApplication {
    }

}
