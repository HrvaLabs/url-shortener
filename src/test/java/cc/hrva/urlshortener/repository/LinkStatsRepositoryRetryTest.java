package cc.hrva.urlshortener.repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static cc.hrva.urlshortener.model.enums.StatDimension.COUNTRY;
import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkStatsRepositoryRetryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private LinkStatsRepository linkStatsRepository;

    @BeforeEach
    void setUp() {
        linkStatsRepository = new LinkStatsRepository(jdbcTemplate);
    }

    @ParameterizedTest
    @MethodSource("increments")
    void retriesOnceWhenAConcurrentClickInsertedTheRowFirst(final Consumer<LinkStatsRepository> increment) {
        when(jdbcTemplate.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("duplicate key")).thenReturn(1);

        assertThatCode(() -> increment.accept(linkStatsRepository)).doesNotThrowAnyException();

        verify(jdbcTemplate, times(2)).update(anyString(), anyMap());
    }

    @ParameterizedTest
    @MethodSource("increments")
    void givesUpAfterTheSecondDuplicate(final Consumer<LinkStatsRepository> increment) {
        when(jdbcTemplate.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("duplicate key"));

        assertThatThrownBy(() -> increment.accept(linkStatsRepository)).isInstanceOf(DataIntegrityViolationException.class);

        verify(jdbcTemplate, times(2)).update(anyString(), anyMap());
    }

    private static Stream<Named<Consumer<LinkStatsRepository>>> increments() {
        return Stream.of(
                Named.of("daily stats", repository -> repository.incrementDailyStats(7, DAY, 1, 1, 0)),
                Named.of("breakdown", repository -> repository.incrementBreakdown(7, DAY, Map.of(REFERRER, "direct", COUNTRY, "HR"))));
    }

}
