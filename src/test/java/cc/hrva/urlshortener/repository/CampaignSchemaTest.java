package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Campaign;
import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.CampaignLinkId;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = CampaignSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:campaign-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class CampaignSchemaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 9, 0);
    private static final String SHAPE_KEY = "a".repeat(64);

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignLinkRepository campaignLinkRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void every034ChangesetRan() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '034-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("034-campaign", "EXECUTED"),
                        tuple("034-campaign-link", "EXECUTED"),
                        tuple("034-reported-domain", "EXECUTED"));
        assertThat(jdbcTemplate.queryForList("select upper(index_name) from information_schema.indexes", String.class))
                .contains("UX_CAMPAIGN_SHAPE_KEY", "IDX_CAMPAIGN_LAST_SEEN_AT", "IDX_CAMPAIGN_LINK_URL_ID", "IDX_REPORTED_DOMAIN_REPORTED_AT");
    }

    @Test
    void aCampaignRoundTripsWithItsQueryNames() {
        final var id = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))).getId();
        final var bare = campaignRepository.saveAndFlush(campaign("b".repeat(64), "/login", List.of())).getId();
        entityManager.clear();

        final var stored = campaignRepository.findByShapeKey(SHAPE_KEY).orElseThrow();

        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getQueryNames()).containsExactly("r", "t");
        assertThat(stored.shape().display()).isEqualTo("/wqamhcsom?r&t");
        assertThat(stored.getStatus()).isEqualTo(CampaignStatus.HOLDING);
        assertThat(stored.getActiveUntil()).isEqualTo(NOW.plusDays(7));
        assertThat(campaignRepository.findById(bare).orElseThrow().getQueryNames()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select query_names from campaign where id = ?", String.class, bare)).isEmpty();
        assertThat(campaignRepository.findByShapeKey("c".repeat(64))).isEmpty();
    }

    @Test
    void shapeKeyIsUnique() {
        campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t")));

        assertThatThrownBy(() -> campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theListIsTheRecentlySeenNewestFirst() {
        final var old = campaign("1".repeat(64), "/old", List.of("a"));
        old.setLastSeenAt(NOW.minusDays(91));
        final var older = campaign("2".repeat(64), "/older", List.of("a"));
        older.setLastSeenAt(NOW.minusDays(3));
        final var newest = campaign("3".repeat(64), "/newest", List.of("a"));
        campaignRepository.saveAllAndFlush(List.of(old, older, newest));

        assertThat(campaignRepository.findByLastSeenAtAfterOrderByLastSeenAtDesc(NOW.minusDays(90)))
                .extracting(Campaign::getPath).containsExactly("/newest", "/older");
    }

    @Test
    void reopeningTakesOnlyAnExpiredRowThatIsNotAllowed() {
        final var expired = campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"));
        expired.setActiveUntil(NOW.minusMinutes(1));
        final var allowed = campaign("b".repeat(64), "/allowed", List.of("a"));
        allowed.setStatus(CampaignStatus.ALLOWED);
        allowed.setActiveUntil(NOW.minusDays(1));
        final var active = campaign("c".repeat(64), "/active", List.of("a"));
        campaignRepository.saveAllAndFlush(List.of(expired, allowed, active));

        assertThat(campaignRepository.reopen(expired.getId(), CampaignStatus.ALERT_ONLY, NOW, NOW.plusDays(3))).isEqualTo(1);
        assertThat(campaignRepository.reopen(expired.getId(), CampaignStatus.HOLDING, NOW, NOW.plusDays(3))).isZero();
        assertThat(campaignRepository.reopen(allowed.getId(), CampaignStatus.HOLDING, NOW, NOW.plusDays(3))).isZero();
        assertThat(campaignRepository.reopen(active.getId(), CampaignStatus.HOLDING, NOW, NOW.plusDays(3))).isZero();
        entityManager.clear();

        final var reopened = campaignRepository.findById(expired.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(CampaignStatus.ALERT_ONLY);
        assertThat(reopened.getFirstSeenAt()).isEqualTo(NOW.minusDays(10));
        assertThat(reopened.getLastSeenAt()).isEqualTo(NOW);
        assertThat(reopened.getAlertedAt()).isEqualTo(NOW);
        assertThat(reopened.getActiveUntil()).isEqualTo(NOW.plusDays(3));
    }

    @Test
    void extendingMovesTheEndAndTheLastSeenAndSeeingMovesOnlyTheLastSeen() {
        final var held = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t")));
        final var allowed = campaign("b".repeat(64), "/allowed", List.of("a"));
        allowed.setStatus(CampaignStatus.ALLOWED);
        campaignRepository.saveAndFlush(allowed);

        campaignRepository.extendHold(held.getId(), NOW.plusHours(2), NOW.plusDays(9));
        campaignRepository.markSeen(allowed.getId(), NOW.plusHours(3));
        entityManager.clear();

        assertThat(campaignRepository.findById(held.getId()).orElseThrow())
                .extracting(Campaign::getLastSeenAt, Campaign::getActiveUntil).containsExactly(NOW.plusHours(2), NOW.plusDays(9));
        assertThat(campaignRepository.findById(allowed.getId()).orElseThrow())
                .extracting(Campaign::getLastSeenAt, Campaign::getActiveUntil).containsExactly(NOW.plusHours(3), NOW.plusDays(7));
    }

    @Test
    void aCampaignsLinksAreFoundByCampaignAndById() {
        final var campaignId = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))).getId();
        final var first = link("aaa");
        final var second = link("bbb");
        campaignLinkRepository.saveAllAndFlush(List.of(campaignLink(campaignId, first, "omaehhm.com"), campaignLink(campaignId, second, "c.com")));
        entityManager.clear();

        assertThat(campaignLinkRepository.findByIdCampaignId(campaignId))
                .extracting(stored -> stored.getId().urlId(), CampaignLink::getDomain, CampaignLink::getCreatedAt)
                .containsExactlyInAnyOrder(tuple(first, "omaehhm.com", NOW), tuple(second, "c.com", NOW));
        assertThat(campaignLinkRepository.existsById(new CampaignLinkId(campaignId, first))).isTrue();
        assertThat(campaignLinkRepository.existsById(new CampaignLinkId(campaignId, first + 1000))).isFalse();
    }

    @Test
    void aLinkIsInACampaignOnce() {
        final var campaignId = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))).getId();
        final var urlId = link("aaa");
        jdbcTemplate.update("insert into campaign_link (campaign_id, url_id, domain, created_at) values (?, ?, ?, ?)", campaignId, urlId, "a.com", NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("insert into campaign_link (campaign_id, url_id, domain, created_at) values (?, ?, ?, ?)",
                campaignId, urlId, "a.com", NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCampaignLinkNeedsAnExistingLink() {
        final var campaignId = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("insert into campaign_link (campaign_id, url_id, domain, created_at) values (?, ?, ?, ?)",
                campaignId, 987_654L, "a.com", NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingALinkDeletesItsCampaignLinks() {
        final var campaignId = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))).getId();
        final var gone = link("aaa");
        final var kept = link("bbb");
        campaignLinkRepository.saveAllAndFlush(List.of(campaignLink(campaignId, gone, "a.com"), campaignLink(campaignId, kept, "b.com")));

        jdbcTemplate.update("delete from url where id = ?", gone);

        assertThat(jdbcTemplate.queryForList("select url_id from campaign_link", Long.class)).containsExactly(kept);
        assertThat(campaignRepository.count()).isEqualTo(1);
    }

    @Test
    void deletingACampaignDeletesItsLinks() {
        final var campaignId = campaignRepository.saveAndFlush(campaign(SHAPE_KEY, "/wqamhcsom", List.of("r", "t"))).getId();
        final var urlId = link("aaa");
        campaignLinkRepository.saveAndFlush(campaignLink(campaignId, urlId, "a.com"));

        jdbcTemplate.update("delete from campaign where id = ?", campaignId);

        assertThat(jdbcTemplate.queryForObject("select count(*) from campaign_link", Long.class)).isZero();
        assertThat(urlRepository.existsById(urlId)).isTrue();
    }

    @Test
    void reportedDomainIsKeyedByDomain() {
        jdbcTemplate.update("insert into reported_domain (domain, registrar, abuse_email, reported_by, reported_at) values (?, ?, ?, ?, ?)",
                "omaehhm.com", "NameSilo, LLC", "abuse@namesilo.com", "karlo@hrva.cc", NOW);
        jdbcTemplate.update("insert into reported_domain (domain, reported_by, reported_at) values (?, ?, ?)", "c.com", "karlo@hrva.cc", NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("insert into reported_domain (domain, reported_by, reported_at) values (?, ?, ?)",
                "omaehhm.com", "karlo@hrva.cc", NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForList("select domain, last_checked_at, suspended_at from reported_domain order by domain"))
                .extracting(row -> row.get("DOMAIN"), row -> row.get("LAST_CHECKED_AT"), row -> row.get("SUSPENDED_AT"))
                .containsExactly(tuple("c.com", null, null), tuple("omaehhm.com", null, null));
    }

    private long link(final String alias) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(alias).longUrl("https://example.com/" + alias).build()).getId();
    }

    private static Campaign campaign(final String shapeKey, final String path, final List<String> queryNames) {
        return Campaign.builder()
                .shapeKey(shapeKey)
                .path(path)
                .queryNames(queryNames)
                .status(CampaignStatus.HOLDING)
                .firstSeenAt(NOW.minusDays(10))
                .lastSeenAt(NOW)
                .activeUntil(NOW.plusDays(7))
                .alertedAt(NOW.minusDays(10))
                .build();
    }

    private static CampaignLink campaignLink(final long campaignId, final long urlId, final String domain) {
        return CampaignLink.builder().id(new CampaignLinkId(campaignId, urlId)).domain(domain).createdAt(NOW).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {CampaignRepository.class, CampaignLinkRepository.class, UrlRepository.class}))
    static class TestApplication {
    }

}
