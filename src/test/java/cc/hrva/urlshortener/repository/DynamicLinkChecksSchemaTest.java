package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.SourceKind;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.groups.Tuple;
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

    private static final String HAGEZI_MIRROR = "https://hagezi-mirror.dnsbunker.org/wildcard/";
    private static final String HAGEZI_GITHUB = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/";
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
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void seedsTheHaGeZiSourcesFromTheirMirrorWithGitHubAsTheFallback() {
        assertThat(blocklistSourceRepository.findAll(Sort.by("id")).subList(0, 5))
                .extracting(BlocklistSource::getName, BlocklistSource::getKind, BlocklistSource::getAction, BlocklistSource::getUrl,
                        BlocklistSource::getFallbackUrls, BlocklistSource::getRefreshIntervalHours)
                .containsExactly(
                        hagezi("HaGeZi TIF medium", BlocklistAction.BLOCK, "tif.medium-onlydomains.txt"),
                        hagezi("HaGeZi Fake", BlocklistAction.BLOCK, "fake-onlydomains.txt"),
                        hagezi("HaGeZi badware hosters", BlocklistAction.FLAG, "hoster-onlydomains.txt"),
                        hagezi("HaGeZi DynDNS", BlocklistAction.FLAG, "dyndns-onlydomains.txt"),
                        hagezi("HaGeZi URL shorteners", BlocklistAction.FLAG, "urlshortener-onlydomains.txt"));
    }

    @Test
    void seedsTheLinkFeedsAndThePhishingDomains() {
        assertThat(blocklistSourceRepository.findAll(Sort.by("id")).subList(5, 9))
                .extracting(BlocklistSource::getName, BlocklistSource::getKind, BlocklistSource::getAction, BlocklistSource::getUrl,
                        BlocklistSource::getFallbackUrls, BlocklistSource::getRefreshIntervalHours)
                .containsExactly(
                        tuple("URLhaus", SourceKind.LINKS, BlocklistAction.BLOCK,
                                "https://urlhaus.abuse.ch/downloads/text_online/", List.of(), 1),
                        tuple("OpenPhish", SourceKind.LINKS, BlocklistAction.BLOCK,
                                "https://raw.githubusercontent.com/openphish/public_feed/refs/heads/main/feed.txt", List.of(), 12),
                        tuple("PhishTank", SourceKind.LINKS, BlocklistAction.BLOCK,
                                "https://data.phishtank.com/data/online-valid.csv", List.of(), 6),
                        tuple("Phishing.Database domains", SourceKind.DOMAINS, BlocklistAction.FLAG,
                                "https://raw.githubusercontent.com/Phishing-Database/Phishing.Database/master/phishing-domains-ACTIVE.txt",
                                List.of(), 24));
        assertThat(blocklistSourceRepository.findAll()).hasSize(9);
        assertThat(blocklistSourceRepository.findByEnabledTrue()).hasSize(9);
    }

    @Test
    void aSourceKeepsItsFallbacksAndRefreshState() {
        final var fetchedAt = LocalDateTime.of(2026, 10, 21, 7, 0);
        final var source = blocklistSourceRepository.saveAndFlush(BlocklistSource.builder()
                .name("Phishing Army")
                .url("https://phishing.army/download/phishing_army_blocklist.txt")
                .action(BlocklistAction.FLAG)
                .kind(SourceKind.DOMAINS)
                .refreshIntervalHours(168)
                .fallbackUrls(List.of("https://mirror-a.example/phishing_army_blocklist.txt", "https://mirror-b.example/phishing_army_blocklist.txt"))
                .lastSuccessUrl("https://mirror-b.example/phishing_army_blocklist.txt")
                .lastFetchedAt(fetchedAt)
                .lastSuccessAt(fetchedAt)
                .domainCount(152_000L)
                .listVersion("2026.1021.0700")
                .etag("W/\"6ad7ef4a-499d4\"")
                .lastError("main: HTTP 503")
                .build());
        entityManager.clear();

        assertThat(blocklistSourceRepository.findById(source.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getFallbackUrls())
                    .containsExactly("https://mirror-a.example/phishing_army_blocklist.txt", "https://mirror-b.example/phishing_army_blocklist.txt");
            assertThat(stored.getLastSuccessUrl()).isEqualTo("https://mirror-b.example/phishing_army_blocklist.txt");
            assertThat(stored.getRefreshIntervalHours()).isEqualTo(168);
            assertThat(stored.getListVersion()).isEqualTo("2026.1021.0700");
            assertThat(stored.getDomainCount()).isEqualTo(152_000L);
            assertThat(stored.getLastError()).isEqualTo("main: HTTP 503");
        });
    }

    @Test
    void aSourceSavedWithoutKindOrIntervalGetsTheColumnDefaults() {
        entityManager.createNativeQuery("INSERT INTO blocklist_source (name, url, action) VALUES ('Raw', 'https://raw.example/list.txt', 'FLAG')")
                .executeUpdate();
        entityManager.clear();

        assertThat(blocklistSourceRepository.findAll()).filteredOn(source -> source.getName().equals("Raw"))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getKind()).isEqualTo(SourceKind.DOMAINS);
                    assertThat(source.getRefreshIntervalHours()).isEqualTo(6);
                    assertThat(source.isEnabled()).isTrue();
                    assertThat(source.getFallbackUrls()).isEmpty();
                });
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
    void aLinkKeepsWhereItsRedirectsEnd() {
        final var checkedAt = LocalDateTime.of(2026, 10, 21, 4, 30);
        final var url = urlRepository.saveAndFlush(Url.builder()
                .shortUrl("chain")
                .longUrl("https://bit.example/abc")
                .finalUrl("https://landing.example/offer?id=1")
                .chainCheckedAt(checkedAt)
                .build());
        entityManager.clear();

        assertThat(urlRepository.findById(url.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getFinalUrl()).isEqualTo("https://landing.example/offer?id=1");
            assertThat(stored.getChainCheckedAt()).isEqualTo(checkedAt);
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

        assertThat(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url))
                .map(LinkReview::getReasons)
                .contains("high-risk domain ending .life\nlogin or verification lure");
        assertThat(linkReviewRepository.findByUrlAndResolvedAtIsNull(url)).hasSize(1);
        assertThat(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url)).isPresent();
        assertThat(linkReviewRepository.countByResolvedAtIsNull()).isEqualTo(1);
    }

    @Test
    void twoOpenReviewsForOneLinkStillGiveTheNewestOneBack() {
        final var url = urlRepository.saveAndFlush(Url.builder().shortUrl("dup").longUrl("https://findfavoritedate.life/?u=dup").build());
        linkReviewRepository.saveAndFlush(LinkReview.builder().url(url).reasons("older").flaggedAt(LocalDateTime.now().minusMinutes(5)).build());
        linkReviewRepository.saveAndFlush(LinkReview.builder().url(url).reasons("newer").flaggedAt(LocalDateTime.now()).build());
        entityManager.clear();

        assertThat(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url))
                .map(LinkReview::getReasons)
                .contains("newer");
        assertThat(linkReviewRepository.findByUrlAndResolvedAtIsNull(url)).hasSize(2);
    }

    @Test
    void openReviewsAVisitorReportedAreCountedByTheirReason() {
        final var reported = urlRepository.saveAndFlush(Url.builder().shortUrl("reported").longUrl("https://findfavoritedate.life/?u=r").build());
        final var flagged = urlRepository.saveAndFlush(Url.builder().shortUrl("flagged").longUrl("https://findfavoritedate.life/?u=f").build());
        final var settled = urlRepository.saveAndFlush(Url.builder().shortUrl("settled").longUrl("https://findfavoritedate.life/?u=s").build());
        linkReviewRepository.saveAndFlush(LinkReview.builder()
                .url(reported)
                .reasons("domain registered in the last 30 days\nreported by a visitor: phishing: asks for my bank login")
                .build());
        linkReviewRepository.saveAndFlush(LinkReview.builder().url(flagged).reasons("domain registered in the last 30 days").build());
        linkReviewRepository.saveAndFlush(LinkReview.builder()
                .url(settled)
                .reasons("reported by a visitor: spam")
                .resolvedAt(LocalDateTime.now())
                .build());
        entityManager.clear();

        assertThat(linkReviewRepository.countByResolvedAtIsNullAndReasonsContaining("reported by a visitor")).isEqualTo(1);
    }

    @Test
    void aReviewWithoutAFlagTimeIsFlaggedWhenSaved() {
        final var url = urlRepository.saveAndFlush(Url.builder().shortUrl("jofogas").longUrl("https://www.jofogas.cards/login").build());
        final var review = linkReviewRepository.saveAndFlush(LinkReview.builder().url(url).reasons("imitates jofogas").build());
        entityManager.clear();

        assertThat(linkReviewRepository.findById(review.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getFlaggedAt()).isNotNull());
    }

    @Test
    void activeDestinationsComeInIdOrderAfterTheGivenId() {
        final var first = savedLink("first", "https://jofogas.cards/a", null);
        final var switchedOff = savedLink("off", "https://jofogas.cards/b", null);
        switchedOff.setActive(false);
        final var second = savedLink("second", "https://www.jofogas.cards/c", null);
        final var third = savedLink("third", "https://example.org/", null);
        entityManager.flush();
        entityManager.clear();

        assertThat(urlRepository.findActiveDestinationsAfter(0L, PageRequest.ofSize(2))).containsExactly(
                new LinkDestination(first.getId(), "https://jofogas.cards/a"),
                new LinkDestination(second.getId(), "https://www.jofogas.cards/c"));
        assertThat(urlRepository.findActiveDestinationsAfter(second.getId(), PageRequest.ofSize(2)))
                .containsExactly(new LinkDestination(third.getId(), "https://example.org/"));
    }

    @Test
    void blockedDestinationsAreFoundByReasonInIdOrder() {
        final var listed = savedBlockedLink("listed", "https://jofogas.cards/a", BlockReason.BLOCKLIST);
        savedBlockedLink("malware", "https://jofogas.cards/b", BlockReason.SAFE_BROWSING);
        final var ruled = savedBlockedLink("ruled", "https://jofogas.cards/c", BlockReason.DOMAIN_RULE);
        savedLink("active", "https://jofogas.cards/d", null);
        entityManager.flush();
        entityManager.clear();

        assertThat(urlRepository.findBlockedDestinationsAfter(
                List.of(BlockReason.BLOCKLIST, BlockReason.SCAM_SIGNALS, BlockReason.DOMAIN_RULE), 0L, PageRequest.ofSize(500)))
                .containsExactly(
                        new LinkDestination(listed.getId(), "https://jofogas.cards/a"),
                        new LinkDestination(ruled.getId(), "https://jofogas.cards/c"));
    }

    @Test
    void aUsersLinksAreFoundByStateAndBlockReason() {
        final var owner = savedUser("scammer@example.com");
        final var someoneElse = savedUser("alice@example.com");
        final var active = savedLink("mine", "https://a.example/", owner);
        final var blocked = savedLink("blocked", "https://b.example/", owner);
        blocked.setActive(false);
        blocked.setBlockReason(BlockReason.USER_BLOCKED);
        final var byRule = savedLink("by-rule", "https://c.example/", owner);
        byRule.setActive(false);
        byRule.setBlockReason(BlockReason.DOMAIN_RULE);
        savedLink("theirs", "https://d.example/", someoneElse);
        entityManager.flush();
        entityManager.clear();

        assertThat(urlRepository.findActiveIdsByOwner(owner)).containsExactly(active.getId());
        assertThat(urlRepository.findIdsByOwnerAndBlockReason(owner, BlockReason.USER_BLOCKED)).containsExactly(blocked.getId());
    }

    @Test
    void anAdminAccountIsRecognisedByItsAuthority() {
        final var admin = savedUser("admin@example.com");
        admin.setAuthorities(List.of(entityManager.find(Authorities.class, 2L)));
        final var user = savedUser("alice@example.com");
        user.setAuthorities(List.of(entityManager.find(Authorities.class, 1L)));
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.existsByIdAndAuthoritiesName(admin.getId(), "ROLE_ADMIN")).isTrue();
        assertThat(userRepository.existsByIdAndAuthoritiesName(user.getId(), "ROLE_ADMIN")).isFalse();
    }

    @Test
    void stampingTheCheckTimeLeavesABlockThatHappenedMeanwhileInPlace() {
        final var checkedAt = LocalDateTime.of(2026, 10, 21, 4, 30);
        final var blocked = savedBlockedLink("malware", "https://jofogas.cards/b", BlockReason.SAFE_BROWSING);
        final var clean = savedLink("clean", "https://example.org/", null);
        final var untouched = savedLink("untouched", "https://example.net/", null);
        entityManager.flush();
        entityManager.clear();

        final var stamped = urlRepository.markSafeBrowsingChecked(List.of(blocked.getId(), clean.getId()), checkedAt);
        entityManager.clear();

        assertThat(stamped).isEqualTo(2);
        assertThat(urlRepository.findById(blocked.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getLastSafeBrowsingCheck()).isEqualTo(checkedAt);
            assertThat(stored.isActive()).isFalse();
            assertThat(stored.getBlockReason()).isEqualTo(BlockReason.SAFE_BROWSING);
        });
        assertThat(urlRepository.findById(clean.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getLastSafeBrowsingCheck()).isEqualTo(checkedAt);
            assertThat(stored.isActive()).isTrue();
        });
        assertThat(urlRepository.findById(untouched.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getLastSafeBrowsingCheck()).isNull());
    }

    @Test
    void aVisitOnlyCountsAndNeverBringsBackALinkBlockedSinceItWasLoaded() {
        final var loadedByTheRedirect = savedLink("visited", "https://jofogas.cards/login", null);
        entityManager.flush();
        entityManager.clear();
        final var blockedMeanwhile = urlRepository.findById(loadedByTheRedirect.getId()).orElseThrow();
        blockedMeanwhile.setActive(false);
        blockedMeanwhile.setBlockReason(BlockReason.BLOCKLIST);
        blockedMeanwhile.setBlockDetail("HaGeZi TIF medium");
        entityManager.flush();
        entityManager.clear();

        urlRepository.recordVisit(loadedByTheRedirect.getId(), LocalDateTime.of(2026, 10, 21, 9, 15));
        final var switchedOff = urlRepository.deactivateAtVisitLimit(loadedByTheRedirect.getId());
        entityManager.clear();

        assertThat(switchedOff).isZero();
        assertThat(urlRepository.findById(loadedByTheRedirect.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.isActive()).isFalse();
            assertThat(stored.getBlockReason()).isEqualTo(BlockReason.BLOCKLIST);
            assertThat(stored.getBlockDetail()).isEqualTo("HaGeZi TIF medium");
            assertThat(stored.getVisits()).isEqualTo(1L);
            assertThat(stored.getLastAccessed()).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 15));
        });
    }

    @Test
    void theVisitThatReachesTheLimitSwitchesTheLinkOffOnce() {
        final var limited = savedLink("limited", "https://example.org/", null);
        limited.setVisits(2L);
        limited.setVisitLimit(3L);
        entityManager.flush();
        entityManager.clear();

        urlRepository.recordVisit(limited.getId(), LocalDateTime.now());
        final var first = urlRepository.deactivateAtVisitLimit(limited.getId());
        final var second = urlRepository.deactivateAtVisitLimit(limited.getId());
        entityManager.clear();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(urlRepository.findById(limited.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getVisits()).isEqualTo(3L);
            assertThat(stored.isActive()).isFalse();
        });
    }

    @Test
    void aVisitBelowTheLimitOrWithoutOneKeepsTheLinkActive() {
        final var belowTheLimit = savedLink("below", "https://example.org/a", null);
        belowTheLimit.setVisits(1L);
        belowTheLimit.setVisitLimit(3L);
        final var withoutALimit = savedLink("unlimited", "https://example.org/b", null);
        withoutALimit.setVisits(100L);
        entityManager.flush();
        entityManager.clear();

        urlRepository.recordVisit(belowTheLimit.getId(), LocalDateTime.now());
        urlRepository.recordVisit(withoutALimit.getId(), LocalDateTime.now());
        final var switchedOff = urlRepository.deactivateAtVisitLimit(belowTheLimit.getId()) + urlRepository.deactivateAtVisitLimit(withoutALimit.getId());
        entityManager.clear();

        assertThat(switchedOff).isZero();
        assertThat(urlRepository.findAllById(List.of(belowTheLimit.getId(), withoutALimit.getId())))
                .extracting(Url::getShortUrl, Url::getVisits, Url::isActive)
                .containsExactlyInAnyOrder(tuple("below", 2L, true), tuple("unlimited", 101L, true));
    }

    @Test
    void oneQueryFindsTheActiveLinkOrElseTheNewestOneThatExplainsWhyItIsUnavailable() {
        final var active = savedLink("reused", "https://example.org/live", null);
        final var newerSwitchedOff = savedLink("reused", "https://example.org/off", null);
        newerSwitchedOff.setActive(false);
        final var older = savedLink("gone", "https://example.org/old", null);
        older.setActive(false);
        final var newer = savedLink("gone", "https://example.org/new", null);
        newer.setActive(false);
        newer.setBlockReason(BlockReason.ADMIN);
        entityManager.flush();
        entityManager.clear();

        assertThat(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("reused")).map(Url::getId).contains(active.getId());
        assertThat(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("gone")).map(Url::getId).contains(newer.getId());
        assertThat(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("unknown")).isEmpty();
    }

    @Test
    void domainRulesAreFoundByType() {
        domainRuleRepository.saveAndFlush(DomainRule.builder().domain("trusted.example").type(DomainRuleType.ALLOW).createdBy("admin@localhost").build());
        entityManager.clear();

        assertThat(domainRuleRepository.findByType(DomainRuleType.ALLOW)).extracting(DomainRule::getDomain).containsExactly("trusted.example");
        assertThat(domainRuleRepository.findByType(DomainRuleType.BLOCK)).extracting(DomainRule::getDomain)
                .contains("njuskaio.cam")
                .doesNotContain("trusted.example");
    }

    private Url savedLink(final String shortUrl, final String longUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl(longUrl).owner(owner).build());
    }

    private Url savedBlockedLink(final String shortUrl, final String longUrl, final BlockReason reason) {
        final var url = savedLink(shortUrl, longUrl, null);
        url.setActive(false);
        url.setBlockReason(reason);
        url.setBlockedAt(LocalDateTime.of(2026, 10, 21, 2, 0));
        return url;
    }

    private User savedUser(final String email) {
        final var user = User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build();
        entityManager.persist(user);
        return user;
    }

    private static Tuple hagezi(final String name, final BlocklistAction action, final String file) {
        return tuple(name, SourceKind.DOMAINS, action, HAGEZI_MIRROR + file, List.of(HAGEZI_GITHUB + file), 6);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, BlocklistSourceRepository.class, DomainRuleRepository.class, LinkReviewRepository.class,
                            UserRepository.class}))
    static class TestApplication {
    }

}
