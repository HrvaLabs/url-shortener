package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SystemStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:system-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class SystemStatsRepositoryTest {

    @Autowired
    private SystemStatsRepository systemStatsRepository;

    @Test
    void databaseInfoNamesH2AndSkipsTheSize() {
        final var database = systemStatsRepository.databaseInfo();

        assertThat(database.product()).isEqualTo("H2");
        assertThat(database.version()).matches("\\d+\\.\\d+");
        assertThat(database.sizeBytes()).isNull();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import(SystemStatsRepository.class)
    static class TestApplication {
    }

}
