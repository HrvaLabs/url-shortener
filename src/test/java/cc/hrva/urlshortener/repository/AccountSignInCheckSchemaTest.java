package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = AccountSignInCheckSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:account-sign-in-check-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AccountSignInCheckSchemaTest {

    @Autowired
    private BlocklistSourceRepository blocklistSourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void the052ChangesetsRunAfter051() {
        assertThat(jdbcTemplate.queryForList("select id from databasechangelog where id like '051-%' or id like '052-%' order by orderexecuted",
                String.class))
                .startsWith("051-virustotal-submitted-id", "052-blocklist-source-tranco");
    }

    @Test
    void seedsTheTrancoTopSitesAsAListOfRealSignInDomains() {
        assertThat(blocklistSourceRepository.findAll())
                .filteredOn(source -> source.getKind() == SourceKind.TOP_SITES)
                .extracting(BlocklistSource::getName, BlocklistSource::getUrl, BlocklistSource::getFallbackUrls, BlocklistSource::getAction,
                        BlocklistSource::isEnabled, BlocklistSource::getRefreshIntervalHours, BlocklistSource::getDomainCount,
                        BlocklistSource::getLastSuccessAt)
                .containsExactly(tuple("Tranco top sites", "https://tranco-list.eu/api/lists/date/latest", List.of(), BlocklistAction.TRUST,
                        true, 24, null, null));
        assertThat(blocklistSourceRepository.findAll())
                .filteredOn(source -> source.getAction() == BlocklistAction.TRUST)
                .extracting(BlocklistSource::getName)
                .containsExactly("Tranco top sites");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = BlocklistSourceRepository.class))
    static class TestApplication {
    }

}
