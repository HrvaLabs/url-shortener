package cc.hrva.urlshortener.service.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainRouteRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomDomainRegistryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-registry;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class CustomDomainRegistryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 9, 0);
    private static final long ANA = 910L;
    private static final long TOM = 911L;
    private static final String CYRILLIC_O = Character.toString(0x43E);

    @Autowired
    private CustomDomainRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final Logger registryLogger = (Logger) LoggerFactory.getLogger(CustomDomainRegistry.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    @BeforeEach
    void setUp() {
        user(ANA, "ana@hrva.cc", true);
        user(TOM, "tom@hrva.cc", false);
        logEvents.start();
        registryLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        registryLogger.detachAppender(logEvents);
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, TOM);
        registry.reload();
    }

    @Test
    void aReloadHoldsTheActiveAndTurnedOffDomainsWithTheirOwnersState() {
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
        final var disabled = domain(ANA, "off.example.com", CustomDomainStatus.DISABLED);
        final var ofAnInactiveOwner = domain(TOM, "tom.example.com", CustomDomainStatus.ACTIVE);

        registry.reload();

        assertThat(registry.route("go.example.com")).contains(new DomainRoute(active, "go.example.com", CustomDomainStatus.ACTIVE, true));
        assertThat(registry.route("off.example.com")).contains(new DomainRoute(disabled, "off.example.com", CustomDomainStatus.DISABLED, true));
        assertThat(registry.route("tom.example.com")).contains(new DomainRoute(ofAnInactiveOwner, "tom.example.com", CustomDomainStatus.ACTIVE, false));
    }

    @Test
    void domainsThatAreNotLiveOrTurnedOffAreNotRouted() {
        for (final var status : CustomDomainStatus.values()) {
            if (status != CustomDomainStatus.ACTIVE && status != CustomDomainStatus.DISABLED) {
                domain(ANA, status.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".example.com", status);
            }
        }

        registry.reload();

        for (final var status : CustomDomainStatus.values()) {
            final var hostname = status.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".example.com";
            assertThat(registry.route(hostname)).as(hostname).isEmpty();
        }
    }

    @Test
    void onlyADomainThatIsActiveWithAnActiveOwnerServes() {
        domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
        domain(ANA, "off.example.com", CustomDomainStatus.DISABLED);
        domain(TOM, "tom.example.com", CustomDomainStatus.ACTIVE);
        domain(TOM, "tom-off.example.com", CustomDomainStatus.DISABLED);

        registry.reload();

        assertThat(registry.active("go.example.com")).isPresent();
        assertThat(registry.active("off.example.com")).isEmpty();
        assertThat(registry.active("tom.example.com")).isEmpty();
        assertThat(registry.active("tom-off.example.com")).isEmpty();
        assertThat(registry.active("nowhere.example.com")).isEmpty();
    }

    @Test
    void servesIsTrueOnlyForAnActiveDomainWithAnActiveOwner() {
        for (final var status : CustomDomainStatus.values()) {
            assertThat(new DomainRoute(1L, "go.example.com", status, true).serves()).as(status.name()).isEqualTo(status == CustomDomainStatus.ACTIVE);
            assertThat(new DomainRoute(1L, "go.example.com", status, false).serves()).as(status.name()).isFalse();
        }
    }

    @Test
    void aLookupIgnoresCapitalsATrailingDotAPortAndReadsUnicodeAsPunycode() {
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
        final var lookAlike = domain(ANA, "xn--g-1tb.example.com", CustomDomainStatus.ACTIVE);

        registry.reload();

        for (final var host : List.of("GO.Example.COM", "go.example.com.", "go.example.com:443", " go.example.com ")) {
            assertThat(registry.route(host).map(DomainRoute::id)).as(host).contains(id);
        }
        assertThat(registry.route("g" + CYRILLIC_O + ".example.com").map(DomainRoute::id)).contains(lookAlike);
        assertThat(registry.route(null)).isEmpty();
        assertThat(registry.route("")).isEmpty();
        assertThat(registry.route("www.go.example.com")).isEmpty();
    }

    @Test
    void aFailedReloadKeepsThePreviousRoutesAndLogsOneWarning() {
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
        registry.reload();
        jdbcTemplate.execute("alter table custom_domain rename to custom_domain_away");
        try {
            registry.reload();
        } finally {
            jdbcTemplate.execute("alter table custom_domain_away rename to custom_domain");
        }

        assertThat(registry.route("go.example.com").map(DomainRoute::id)).contains(id);
        assertThat(logEvents.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).startsWith("Could not reload the custom domain routes, keeping the previous ones");
        });
    }

    @Test
    void aDomainChangeReloadsTheRoutesOnceItsTransactionCommits() {
        final var seenInsideTheTransaction = transactionOperations.execute(status -> {
            final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
            eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
            return registry.route("go.example.com");
        });

        assertThat(seenInsideTheTransaction).isEmpty();
        assertThat(registry.route("go.example.com")).isPresent();
    }

    @Test
    void aTurnOffIsSeenOnceItsTransactionCommits() {
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
        registry.reload();

        transactionOperations.executeWithoutResult(status -> {
            jdbcTemplate.update("update custom_domain set status = 'DISABLED' where id = ?", id);
            eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        });

        assertThat(registry.route("go.example.com").map(DomainRoute::status)).contains(CustomDomainStatus.DISABLED);
        assertThat(registry.active("go.example.com")).isEmpty();
    }

    @Test
    void aRolledBackChangeReloadsNothing() {
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE);
        registry.reload();

        jdbcTemplate.update("update custom_domain set status = 'DELETED', hostname_key = null where id = ?", id);

        transactionOperations.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
            status.setRollbackOnly();
        });

        assertThat(registry.route("go.example.com").map(DomainRoute::status)).isEqualTo(Optional.of(CustomDomainStatus.ACTIVE));
    }

    @Test
    void theRoutesAreLoadedOnceEveryBeanExistsSoBeforeTheServerTakesRequests() {
        final var repository = mock(CustomDomainRepository.class);
        when(repository.findRoutes()).thenReturn(List.of(new DomainRouteRow(7L, "go.example.com", "go.example.com", CustomDomainStatus.ACTIVE, 5L, true)));
        final var loaded = new CustomDomainRegistry(repository, mock(PlatformTransactionManager.class));

        assertThat(loaded).isInstanceOf(SmartInitializingSingleton.class);
        ((SmartInitializingSingleton) loaded).afterSingletonsInstantiated();

        assertThat(loaded.route("go.example.com").map(DomainRoute::id)).contains(7L);
    }

    @Test
    void theReloadReadsInItsOwnShortReadOnlyTransaction() {
        final var repository = mock(CustomDomainRepository.class);
        final var transactionManager = mock(PlatformTransactionManager.class);
        final var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        when(repository.findRoutes()).thenReturn(List.of());

        new CustomDomainRegistry(repository, transactionManager).reload();

        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
        verify(transactionManager).commit(any());
    }

    private void user(final long id, final String email, final boolean active) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, ?, 'secret-hash', ?, ?)",
                id, email, active, NOW.minusDays(30));
    }

    private long domain(final long ownerId, final String hostname, final CustomDomainStatus status) {
        final var holdsHostname = CustomDomainStatus.HOLDS_HOSTNAME.contains(status);
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_delete_pending, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', false, ?, ?)""",
                ownerId, hostname, holdsHostname ? hostname : null, status.name(), NOW, NOW);
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = CustomDomainRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CustomDomainRepository.class))
    @Import(CustomDomainRegistry.class)
    static class TestApplication {
    }

}
