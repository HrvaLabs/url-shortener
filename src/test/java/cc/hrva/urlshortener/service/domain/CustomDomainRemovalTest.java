package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse.DomainRename;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlDomainMoves;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval.Actor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomDomainRemovalTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-removal;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test"
        })
class CustomDomainRemovalTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final long ANA = 820L;
    private static final Actor OWNER = new Actor("ana@hrva.cc", false);
    private static final Actor ADMIN = new Actor("admin@hrva.cc", true);

    @Autowired
    private CustomDomainRemoval customDomainRemoval;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TestApplication.CommittedChanges committedChanges;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private CloudflareClient cloudflareClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, 'ana@hrva.cc', 'secret-hash', true, ?)",
                ANA, NOW.minusDays(30));
        when(urlService.nextFreeShortUrl(isNull())).thenReturn("k3J9x", "r4nd0", "z9y8x");
        committedChanges.clear();
    }

    @AfterEach
    void tearDown() {
        cacheManager.getCache("urls").clear();
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from user_account where id = ?", ANA);
    }

    @Test
    void deactivateTurnsLinksOffAndKeepsTheirDomain() {
        final var domain = domain("go.example.com", "ACTIVE", "cf-1");
        final var menu = link(domain, "menu", true);
        final var cafe = link(domain, "cafe", true);
        final var old = link(domain, "old", false);

        final var response = customDomainRemoval.remove(domain, DomainLinkMode.DEACTIVATE, OWNER);

        assertThat(response).isEqualTo(new DomainRemovalResponse(0, List.of(), 2));
        assertThat(jdbcTemplate.queryForList("select active from url where domain_id = ? order by id", Boolean.class, domain))
                .containsExactly(false, false, false);
        assertThat(jdbcTemplate.queryForList("select id from url where domain_id = ? order by id", Long.class, domain))
                .containsExactly(menu, cafe, old);
        assertThat(statusOf(domain)).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("select deleted_at from custom_domain where id = ?", LocalDateTime.class, domain)).isEqualTo(NOW);
        verifyNoInteractions(urlService, reservedAliasService);
    }

    @Test
    void moveKeepsFreeAliases() {
        final var domain = domain("go.example.com", "ACTIVE", "cf-1");
        final var menu = link(domain, "menu", true);
        final var old = link(domain, "old", false);

        final var response = customDomainRemoval.remove(domain, DomainLinkMode.MOVE, OWNER);

        assertThat(response).isEqualTo(new DomainRemovalResponse(2, List.of(), 0));
        assertThat(jdbcTemplate.queryForMap("select domain_id, short_url, active from url where id = ?", menu))
                .containsEntry("DOMAIN_ID", null).containsEntry("SHORT_URL", "menu").containsEntry("ACTIVE", true);
        assertThat(jdbcTemplate.queryForMap("select domain_id, short_url, active from url where id = ?", old))
                .containsEntry("DOMAIN_ID", null).containsEntry("SHORT_URL", "old").containsEntry("ACTIVE", false);
        assertThat(statusOf(domain)).isEqualTo("DELETED");
        verify(urlService, never()).nextFreeShortUrl(isNull());
    }

    @Test
    void moveRenamesWhenHrvaHasTheAlias() {
        final var domain = domain("go.example.com", "ACTIVE", "cf-1");
        hrvaLink("menu", false);
        final var menu = link(domain, "menu", true);
        final var promo = link(domain, "promo", true);
        final var cafe = link(domain, "cafe", true);
        when(reservedAliasService.isReserved(anyString())).thenAnswer(invocation -> "promo".equals(invocation.getArgument(0)));

        final var response = customDomainRemoval.remove(domain, DomainLinkMode.MOVE, OWNER);

        assertThat(response).isEqualTo(new DomainRemovalResponse(3, List.of(new DomainRename("menu", "k3J9x"), new DomainRename("promo", "r4nd0")), 0));
        assertThat(aliasOf(menu)).isEqualTo("k3J9x");
        assertThat(aliasOf(promo)).isEqualTo("r4nd0");
        assertThat(aliasOf(cafe)).isEqualTo("cafe");
        assertThat(jdbcTemplate.queryForObject("select count(*) from url where domain_id is null and short_url = 'menu'", Integer.class)).isOne();
    }

    @Test
    void aRaceOnTheAliasRetriesWithARandomOne() {
        final var domain = domain("go.example.com", "ACTIVE", "cf-1");
        final var menu = link(domain, "menu", true);
        final var planted = new AtomicBoolean();
        when(reservedAliasService.isReserved(anyString())).thenAnswer(invocation -> {
            if ("menu".equals(invocation.getArgument(0)) && planted.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> hrvaLink("menu", true)).join();
            }
            return false;
        });

        final var response = customDomainRemoval.remove(domain, DomainLinkMode.MOVE, OWNER);

        assertThat(planted).isTrue();
        assertThat(response).isEqualTo(new DomainRemovalResponse(1, List.of(new DomainRename("menu", "k3J9x")), 0));
        assertThat(jdbcTemplate.queryForMap("select domain_id, short_url from url where id = ?", menu))
                .containsEntry("DOMAIN_ID", null).containsEntry("SHORT_URL", "k3J9x");
        assertThat(jdbcTemplate.queryForObject("select count(*) from url where domain_id is null and short_url = 'menu'", Integer.class)).isOne();
    }

    @Test
    void everyKeyIsEvicted() {
        final var moving = domain("go.example.com", "ACTIVE", "cf-1");
        final var deactivating = domain("links.example.org", "ACTIVE", "cf-2");
        link(moving, "menu", true);
        link(deactivating, "cafe", true);
        final var urls = cacheManager.getCache("urls");
        urls.put(moving + "/menu", "cached");
        urls.put("menu", "stale");
        urls.put(deactivating + "/cafe", "cached");
        urls.put("cafe", "the hrva.cc link");

        customDomainRemoval.remove(moving, DomainLinkMode.MOVE, OWNER);
        customDomainRemoval.remove(deactivating, DomainLinkMode.DEACTIVATE, ADMIN);

        assertThat(urls.get(moving + "/menu")).isNull();
        assertThat(urls.get("menu")).isNull();
        assertThat(urls.get(deactivating + "/cafe")).isNull();
        assertThat(urls.get("cafe")).isNotNull();
    }

    @Test
    void theCloudflareDeleteIsQueuedNotCalled() {
        final var created = domain("go.example.com", "AWAITING_DNS", "cf-1");
        final var neverCreated = domain("links.example.org", "APPROVED_PROVISIONING", null);

        customDomainRemoval.remove(created, DomainLinkMode.DEACTIVATE, ADMIN);
        customDomainRemoval.remove(neverCreated, DomainLinkMode.MOVE, ADMIN);

        assertThat(jdbcTemplate.queryForMap("select status, cloudflare_delete_pending, hostname_key from custom_domain where id = ?", created))
                .containsEntry("STATUS", "DELETED").containsEntry("CLOUDFLARE_DELETE_PENDING", true).containsEntry("HOSTNAME_KEY", "go.example.com");
        assertThat(jdbcTemplate.queryForMap("select status, cloudflare_delete_pending, hostname_key from custom_domain where id = ?", neverCreated))
                .containsEntry("STATUS", "DELETED").containsEntry("CLOUDFLARE_DELETE_PENDING", false).containsEntry("HOSTNAME_KEY", null);
        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void everyStatusCanBeRemovedAndAPendingApplicationIgnoresItsLinksMode() {
        for (final var status : List.of("PENDING_REVIEW", "APPROVED_PROVISIONING", "AWAITING_DNS", "ACTIVE", "DISABLED", "ERROR", "REJECTED")) {
            final var id = domain(status.toLowerCase(Locale.ROOT).replace('_', '-') + ".example.com", status, null);

            assertThat(customDomainRemoval.remove(id, DomainLinkMode.MOVE, OWNER)).isEqualTo(DomainRemovalResponse.nothing());

            assertThat(statusOf(id)).isEqualTo("DELETED");
        }
    }

    @Test
    void aRemovedOrUnknownDomainIsNotFound() {
        final var id = domain("go.example.com", "ACTIVE", null);
        customDomainRemoval.remove(id, DomainLinkMode.DEACTIVATE, OWNER);

        assertThatThrownBy(() -> customDomainRemoval.remove(id, DomainLinkMode.DEACTIVATE, ADMIN))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage("Custom domain not found");
        assertThatThrownBy(() -> customDomainRemoval.remove(id + 1000, DomainLinkMode.MOVE, ADMIN))
                .isInstanceOf(CustomDomainNotFoundException.class);
        assertThat(committedChanges.changes()).containsExactly(new CustomDomainChangedEvent(id));
    }

    @Test
    void theChangeIsAnnouncedOnceTheMarkCommitsAndTheMovesRunOutsideIt() {
        final var domain = domain("go.example.com", "ACTIVE", "cf-1");
        link(domain, "menu", true);
        final var seenDuringTheMove = new CopyOnWriteArrayList<String>();
        when(reservedAliasService.isReserved(anyString())).thenAnswer(invocation -> {
            seenDuringTheMove.add(CompletableFuture.supplyAsync(() -> statusOf(domain)).join());
            seenDuringTheMove.add(String.valueOf(committedChanges.changes().size()));
            return false;
        });

        customDomainRemoval.remove(domain, DomainLinkMode.MOVE, OWNER);

        assertThat(seenDuringTheMove).containsExactly("DELETED", "1");
        assertThat(committedChanges.changes()).containsExactly(new CustomDomainChangedEvent(domain));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private long domain(final String hostname, final String status, final String cloudflareId) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_id, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?, ?)""",
                ANA, hostname, "REJECTED".equals(status) ? null : hostname, status, cloudflareId, NOW.minusDays(1), NOW.minusDays(1));
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private long link(final long domainId, final String alias, final boolean active) {
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values (?, ?, ?, ?, ?)",
                alias, "https://example.org/" + alias, ANA, domainId, active);
        return jdbcTemplate.queryForObject("select id from url where domain_id = ? and short_url = ?", Long.class, domainId, alias);
    }

    private void hrvaLink(final String alias, final boolean active) {
        jdbcTemplate.update("insert into url (short_url, long_url, active) values (?, ?, ?)", alias, "https://example.net/" + alias, active);
    }

    private String aliasOf(final long urlId) {
        return jdbcTemplate.queryForObject("select short_url from url where id = ?", String.class, urlId);
    }

    private String statusOf(final long id) {
        return jdbcTemplate.queryForObject("select status from custom_domain where id = ?", String.class, id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, CustomDomainRepository.class, UrlRepository.class}))
    @Import({
            AppConfiguration.class,
            CustomDomainRemoval.class,
            UrlDomainMoves.class,
            UrlCache.class,
            TestApplication.Fixed.class,
            TestApplication.CommittedChanges.class
    })
    static class TestApplication {

        static class CommittedChanges {

            private final List<CustomDomainChangedEvent> changes = new CopyOnWriteArrayList<>();

            @TransactionalEventListener
            public void onChange(final CustomDomainChangedEvent change) {
                changes.add(change);
            }

            List<CustomDomainChangedEvent> changes() {
                return List.copyOf(changes);
            }

            void clear() {
                changes.clear();
            }

        }

        @TestConfiguration
        static class Fixed {

            @Bean
            Clock clock() {
                return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
            }

            @Bean
            CacheManager cacheManager() {
                return new ConcurrentMapCacheManager("urls");
            }

        }

    }

}
