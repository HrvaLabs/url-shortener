package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.codebook.Authorities;
import java.util.List;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CurrentUserAuthoritiesOutsideTransactionTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:current-user-authorities;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class CurrentUserAuthoritiesOutsideTransactionTest {

    private static final long OWNER = 100L;
    private static final String OWNER_EMAIL = "owner@example.com";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTheOwner() {
        jdbcTemplate.update("insert into user_account (id, email, password) values (?, ?, 'secret-hash')", OWNER, OWNER_EMAIL);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, 1)", OWNER);
    }

    @AfterEach
    void removeTheOwner() {
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account where id = ?", OWNER);
    }

    @Test
    void theOwnersRolesStayReadableAfterTheTransactionThatLoadedThem() {
        assertThat(roleNames()).containsExactly("ROLE_USER");
    }

    private List<String> roleNames() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        final var owner = readOnly.execute(status -> userRepository.findByEmail(OWNER_EMAIL).orElseThrow());
        return owner.getAuthorities().stream().map(Authorities::getName).toList();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, UserRepository.class}))
    static class TestApplication {
    }

}
