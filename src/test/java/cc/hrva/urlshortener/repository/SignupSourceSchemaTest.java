package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignupSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = SignupSourceSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:signup-source-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class SignupSourceSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void theSignupSourceColumnExistsAndIsNullable() {
        assertThat(jdbcTemplate.queryForMap("select is_nullable, character_maximum_length from information_schema.columns "
                + "where lower(table_name) = 'user_account' and lower(column_name) = 'signup_source'"))
                .containsEntry("IS_NULLABLE", "YES")
                .containsEntry("CHARACTER_MAXIMUM_LENGTH", 32L);
    }

    @Test
    void anAccountWithoutAnAnswerStoresNull() {
        final var user = User.builder().email("none@example.com").password("correct horse battery").apiKeySlots(4L).build();
        entityManager.persist(user);
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("select signup_source from user_account where id = ?", String.class, user.getId())).isNull();
    }

    @Test
    void theAnswerIsStoredByName() {
        final var user = User.builder().email("answered@example.com").password("correct horse battery").apiKeySlots(4L)
                .signupSource(SignupSource.WEBSITE_OR_LIST).build();
        entityManager.persist(user);
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("select signup_source from user_account where id = ?", String.class, user.getId()))
                .isEqualTo("WEBSITE_OR_LIST");
    }

    @Test
    void theConditionalAnswerChangesOneRowOnlyWhileUnanswered() {
        final var user = User.builder().email("race@example.com").password("correct horse battery").apiKeySlots(4L).build();
        entityManager.persist(user);
        entityManager.flush();
        final var update = "update User u set u.signupSource = :source where u.id = :id and u.signupSource is null";

        final var first = entityManager.createQuery(update).setParameter("source", SignupSource.OTHER).setParameter("id", user.getId()).executeUpdate();
        final var second = entityManager.createQuery(update).setParameter("source", SignupSource.SEARCH_ENGINE).setParameter("id", user.getId()).executeUpdate();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbcTemplate.queryForObject("select signup_source from user_account where id = ?", String.class, user.getId())).isEqualTo("OTHER");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    static class TestApplication {
    }

}
