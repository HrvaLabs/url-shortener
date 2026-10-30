package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
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

@Transactional
@SpringBootTest(
        classes = DuplicateShortUrlLookupTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/short-url-duplicates-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:duplicate-short-url-lookup;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class DuplicateShortUrlLookupTest {

    private static final long OLDER = 1L;
    private static final long NEWER = 2L;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theActiveLinkWinsOverANewerSwitchedOffOne() {
        switchOff(NEWER);

        assertThat(urlRepository.findServed(null, "dup")).map(Url::getId).contains(OLDER);
    }

    @Test
    void theNewestLinkExplainsWhyWhenNoneIsActive() {
        switchOff(OLDER);
        switchOff(NEWER);

        assertThat(urlRepository.findServed(null, "dup")).map(Url::getId).contains(NEWER);
    }

    private void switchOff(final long id) {
        jdbcTemplate.update("update url set active = false where id = ?", id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UrlRepository.class))
    static class TestApplication {
    }

}
