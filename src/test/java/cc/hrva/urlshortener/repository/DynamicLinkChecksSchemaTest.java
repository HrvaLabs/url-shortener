package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = DynamicLinkChecksSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:dynamic-link-checks-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class DynamicLinkChecksSchemaTest {

    private static final String HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/";
    private static final String INCIDENT = "phishing campaign, September 2026";

    @Autowired
    private BlocklistSourceRepository blocklistSourceRepository;

    @Autowired
    private DomainRuleRepository domainRuleRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void seedsTheHaGeZiSources() {
        assertThat(blocklistSourceRepository.findAll(Sort.by("id")))
                .extracting(BlocklistSource::getName, BlocklistSource::getAction, BlocklistSource::getUrl)
                .containsExactly(
                        tuple("HaGeZi TIF medium", BlocklistAction.BLOCK, HAGEZI + "tif.medium-onlydomains.txt"),
                        tuple("HaGeZi Fake", BlocklistAction.BLOCK, HAGEZI + "fake-onlydomains.txt"),
                        tuple("HaGeZi badware hosters", BlocklistAction.FLAG, HAGEZI + "hoster-onlydomains.txt"),
                        tuple("HaGeZi DynDNS", BlocklistAction.FLAG, HAGEZI + "dyndns-onlydomains.txt"),
                        tuple("HaGeZi URL shorteners", BlocklistAction.FLAG, HAGEZI + "urlshortener-onlydomains.txt"));
        assertThat(blocklistSourceRepository.findByEnabledTrue()).hasSize(5);
    }

    @Test
    void seedsTheIncidentDomainBlocks() {
        assertThat(domainRuleRepository.findAll())
                .extracting(DomainRule::getDomain, DomainRule::getType, DomainRule::getReason, DomainRule::getCreatedBy)
                .containsExactlyInAnyOrder(
                        tuple("njuskaio.cam", DomainRuleType.BLOCK, INCIDENT, "system"),
                        tuple("jofogas.cards", DomainRuleType.BLOCK, INCIDENT, "system"),
                        tuple("info-auth.com", DomainRuleType.BLOCK, INCIDENT, "system"),
                        tuple("app-onlinebanking.de", DomainRuleType.BLOCK, INCIDENT, "system"));
        assertThat(domainRuleRepository.findByDomainIn(List.of("njuskaio.cam", "example.com")))
                .extracting(DomainRule::getDomain)
                .containsExactly("njuskaio.cam");
        assertThat(domainRuleRepository.findByDomain("jofogas.cards")).isPresent();
    }

    @Test
    void aDomainCanHaveOnlyOneRule() {
        final var duplicate = DomainRule.builder()
                .domain("njuskaio.cam")
                .type(DomainRuleType.ALLOW)
                .reason("false positive")
                .createdBy("admin@localhost")
                .build();

        assertThatThrownBy(() -> domainRuleRepository.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBlockedLinkKeepsItsReasonDetailAndTime() {
        final var blockedAt = LocalDateTime.of(2026, 10, 21, 2, 0);
        final var url = urlRepository.saveAndFlush(Url.builder()
                .shortUrl("njuskaio_verify")
                .longUrl("https://njuskaio.cam/a/MAUB3")
                .blockReason(BlockReason.DOMAIN_RULE)
                .blockDetail("njuskaio.cam")
                .blockedAt(blockedAt)
                .build());
        entityManager.clear();

        assertThat(urlRepository.findById(url.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
            assertThat(stored.getBlockDetail()).isEqualTo("njuskaio.cam");
            assertThat(stored.getBlockedAt()).isEqualTo(blockedAt);
        });
    }

    @Test
    void anOpenReviewIsFoundThroughItsLink() {
        final var url = urlRepository.saveAndFlush(Url.builder().shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").build());
        linkReviewRepository.saveAndFlush(LinkReview.builder()
                .url(url)
                .reasons("high-risk domain ending .life\nlogin or verification lure")
                .flaggedAt(LocalDateTime.now())
                .build());
        entityManager.clear();

        assertThat(linkReviewRepository.findByUrlAndResolvedAtIsNull(url))
                .map(LinkReview::getReasons)
                .contains("high-risk domain ending .life\nlogin or verification lure");
        assertThat(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url)).isPresent();
        assertThat(linkReviewRepository.findByResolvedAtIsNull(PageRequest.of(0, 20))).hasSize(1);
        assertThat(linkReviewRepository.countByResolvedAtIsNull()).isEqualTo(1);
    }

    @Test
    void aReviewWithoutAFlagTimeIsFlaggedWhenSaved() {
        final var url = urlRepository.saveAndFlush(Url.builder().shortUrl("jofogas").longUrl("https://www.jofogas.cards/login").build());
        final var review = linkReviewRepository.saveAndFlush(LinkReview.builder().url(url).reasons("imitates jofogas").build());
        entityManager.clear();

        assertThat(linkReviewRepository.findById(review.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getFlaggedAt()).isNotNull());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, BlocklistSourceRepository.class, DomainRuleRepository.class, LinkReviewRepository.class}))
    static class TestApplication {
    }

}
