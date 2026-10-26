package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = BioPageLockTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-page-lock;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=200",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageLockTest {

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BioPageLock bioPageLock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private TransactionTemplate otherTransaction;
    private User ana;
    private BioPage page;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        otherTransaction = new TransactionTemplate(transactionManager);
        otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).build());
        page = bioPageRepository.saveAndFlush(BioPage.builder().owner(ana).handle("ana").displayName("Ana").build());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from bio_page where user_id = ?", ana.getId());
        jdbcTemplate.update("delete from user_account where id = ?", ana.getId());
    }

    @Test
    void theLockHoldsThePageRowUntilTheTransactionEnds() {
        transaction.executeWithoutResult(status -> {
            assertThat(bioPageLock.lock(page)).isPresent();

            assertThatThrownBy(() -> otherTransaction.executeWithoutResult(other -> bioPageRepository.findByIdForUpdate(page.getId())))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            final String readWithoutALock = otherTransaction.execute(other -> bioPageRepository.findById(page.getId()).orElseThrow().getHandle());
            assertThat(readWithoutALock).isEqualTo("ana");
        });

        final Optional<BioPage> lockedAfterwards = otherTransaction.execute(other -> bioPageRepository.findByIdForUpdate(page.getId()));
        assertThat(lockedAfterwards).isPresent();
    }

    @Test
    void thePageIsReadAgainOnceLockedEvenWhenItWasLoadedBefore() {
        transaction.executeWithoutResult(status -> {
            final var loadedBefore = bioPageRepository.findByOwner(ana).orElseThrow();
            otherTransaction.executeWithoutResult(other ->
                    jdbcTemplate.update("update bio_page set display_name = 'Ana Horvat', hidden_reason = 'Spam' where id = ?", page.getId()));

            final var locked = bioPageLock.lock(loadedBefore).orElseThrow();

            assertThat(locked.getId()).isEqualTo(page.getId());
            assertThat(locked.getDisplayName()).isEqualTo("Ana Horvat");
            assertThat(locked.getHiddenReason()).isEqualTo("Spam");
        });
    }

    @Test
    void aPageDeletedAfterItWasLoadedIsGoneOnceLocked() {
        transaction.executeWithoutResult(status -> {
            final var loadedBefore = bioPageRepository.findByOwner(ana).orElseThrow();
            otherTransaction.executeWithoutResult(other -> jdbcTemplate.update("delete from bio_page where id = ?", page.getId()));

            assertThat(bioPageLock.lock(loadedBefore)).isEmpty();
        });
    }

    @Test
    void aLockOutsideAWriteTransactionIsRefused() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> bioPageLock.lock(page))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> bioPageLock.lock(page)))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessage("Locking a bio page needs a read-write transaction");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BioPage.class)
    @EnableJpaRepositories(
            basePackageClasses = BioPageRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {BioPageRepository.class, UserRepository.class}))
    @Import(BioPageLock.class)
    static class TestApplication {
    }

}
