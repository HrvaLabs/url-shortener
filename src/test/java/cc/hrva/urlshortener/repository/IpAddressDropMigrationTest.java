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
        classes = IpAddressDropMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/ip-address-gone-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:ip-address-drop-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class IpAddressDropMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void bothDropsAreMarkedRanWhenTheTableAndSequenceAreAlreadyGone() {
        assertThat(jdbcTemplate.queryForList(
                "select id, exectype from databasechangelog where id like '026-drop-ip-address%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("026-drop-ip-address", "MARK_RAN"),
                        tuple("026-drop-ip-address-seq", "MARK_RAN"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
