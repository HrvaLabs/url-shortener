package cc.hrva.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest(
        classes = DomainRemovedByAdminMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/domain-removed-by-admin-migration-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:domain-removed-by-admin-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class DomainRemovedByAdminMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aRemovedDomainThatHrvaTurnedOffIsMarkedAsRemovedByHrva() {
        assertThat(flagOf("admin.example.com")).isTrue();
        assertThat(flagOf("rule.example.com")).isTrue();
        assertThat(flagOf("blocklist.example.com")).isTrue();
    }

    @Test
    void aRemovedDomainOfABlockedAccountIsLeftAlone() {
        assertThat(flagOf("blocked-account.example.com")).isFalse();
    }

    @Test
    void aRemovedDomainThatWasNeverTurnedOffIsLeftAlone() {
        assertThat(flagOf("owner.example.com")).isFalse();
    }

    @Test
    void aDomainThatIsNotRemovedIsLeftAlone() {
        assertThat(flagOf("still-off.example.com")).isFalse();
        assertThat(flagOf("active.example.com")).isFalse();
    }

    @Test
    void onlyTheThreeRemovedDomainsAreMarked() {
        assertThat(jdbcTemplate.query("select hostname from custom_domain where removed_by_admin = true order by hostname",
                (row, index) -> tuple(row.getString("hostname"))))
                .containsExactly(tuple("admin.example.com"), tuple("blocklist.example.com"), tuple("rule.example.com"));
    }

    private Boolean flagOf(final String hostname) {
        return jdbcTemplate.queryForObject("select removed_by_admin from custom_domain where hostname = ?", Boolean.class, hostname);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
