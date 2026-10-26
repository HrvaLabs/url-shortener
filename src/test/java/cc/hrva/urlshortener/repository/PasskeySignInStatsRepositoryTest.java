package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = PasskeySignInStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:passkey-sign-in-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class PasskeySignInStatsRepositoryTest {

    @Autowired
    private PasskeySignInStatsRepository passkeySignInStatsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoSignInsOnADayCountTwo() {
        final var day = LocalDate.of(2026, 10, 24);

        passkeySignInStatsRepository.increment(day);
        passkeySignInStatsRepository.increment(day);

        assertThat(signInsOn(day)).isEqualTo(2L);
    }

    @Test
    void daysAreCountedSeparately() {
        final var day = LocalDate.of(2026, 11, 1);

        passkeySignInStatsRepository.increment(day);
        passkeySignInStatsRepository.increment(day.plusDays(1));
        passkeySignInStatsRepository.increment(day.plusDays(1));

        assertThat(signInsOn(day.minusDays(1))).isZero();
        assertThat(signInsOn(day)).isEqualTo(1L);
        assertThat(signInsOn(day.plusDays(1))).isEqualTo(2L);
    }

    @Test
    void aDatabaseErrorNeverThrows() {
        final var withoutTheTable = new PasskeySignInStatsRepository(new NamedParameterJdbcTemplate(
                new DriverManagerDataSource("jdbc:h2:mem:passkey-sign-in-stats-without-schema;DB_CLOSE_DELAY=-1", "sa", "")));

        assertThatCode(() -> withoutTheTable.increment(LocalDate.of(2026, 12, 1))).doesNotThrowAnyException();
    }

    @Test
    void theFirstSignInOfADayRacingAnotherIsRetriedOnce() {
        final var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("duplicate key")).thenReturn(1);

        assertThatCode(() -> new PasskeySignInStatsRepository(jdbcTemplate).increment(LocalDate.of(2026, 12, 2)))
                .doesNotThrowAnyException();

        verify(jdbcTemplate, times(2)).update(anyString(), anyMap());
    }

    @Test
    void aSecondDuplicateIsDroppedWithoutThrowing() {
        final var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("duplicate key"));

        assertThatCode(() -> new PasskeySignInStatsRepository(jdbcTemplate).increment(LocalDate.of(2026, 12, 3)))
                .doesNotThrowAnyException();

        verify(jdbcTemplate, times(2)).update(anyString(), anyMap());
    }

    private long signInsOn(final LocalDate day) {
        return jdbcTemplate.queryForList("select sign_ins from passkey_sign_in_daily where stat_date = ?", Long.class, day)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    @Import(PasskeySignInStatsRepository.class)
    static class TestApplication {
    }

}
