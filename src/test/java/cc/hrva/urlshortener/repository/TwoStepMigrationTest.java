package cc.hrva.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TwoStepMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/two-step-migration-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:two-step-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class TwoStepMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void turnsTwoStepOnForAccountsWithTotp() {
        assertThat(twoStepEnabled("totp@example.com")).isTrue();
    }

    @Test
    void leavesOtherAccountsOff() {
        assertThat(twoStepEnabled("plain@example.com")).isFalse();
    }

    private Boolean twoStepEnabled(final String email) {
        return jdbcTemplate.queryForObject("select two_step_enabled from user_account where email = ?", Boolean.class, email);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
