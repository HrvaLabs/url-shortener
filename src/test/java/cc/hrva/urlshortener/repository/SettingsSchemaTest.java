package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.AppSetting;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
        classes = SettingsSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:settings-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class SettingsSchemaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 9, 0);

    @Autowired
    private AppSettingRepository appSettingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void the033ChangesetRan() {
        assertThat(jdbcTemplate.queryForList("select exectype from databasechangelog where id = '033-app-setting'", String.class))
                .containsExactly("EXECUTED");
    }

    @Test
    void aSettingRowRoundTripsWithANullValue() {
        appSettingRepository.saveAndFlush(AppSetting.builder()
                .settingKey("CAMPAIGN_LINKS")
                .settingValue(null)
                .changedBy("admin@localhost")
                .changedAt(NOW)
                .build());
        entityManager.clear();

        final var stored = appSettingRepository.findById("CAMPAIGN_LINKS").orElseThrow();

        assertThat(stored.getSettingValue()).isNull();
        assertThat(stored.getChangedBy()).isEqualTo("admin@localhost");
        assertThat(stored.getChangedAt()).isEqualTo(NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AppSetting.class)
    @EnableJpaRepositories(
            basePackageClasses = AppSettingRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = AppSettingRepository.class))
    static class TestApplication {
    }

}
