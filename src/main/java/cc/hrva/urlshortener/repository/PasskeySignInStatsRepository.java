package cc.hrva.urlshortener.repository;

import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PasskeySignInStatsRepository {

    private static final String DAY = "day";

    private static final String INCREMENT = """
            MERGE INTO passkey_sign_in_daily t
            USING (VALUES (CAST(:day AS DATE))) AS s(stat_date)
            ON t.stat_date = s.stat_date
            WHEN MATCHED THEN UPDATE SET sign_ins = t.sign_ins + 1
            WHEN NOT MATCHED THEN INSERT (stat_date, sign_ins) VALUES (s.stat_date, 1)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void increment(final LocalDate day) {
        try {
            updateRetryingOnce(Map.of(DAY, day));
        } catch (final DataAccessException exception) {
            log.warn("Passkey sign-in not counted day={} exception={}", day, exception.getClass().getName());
        }
    }

    private void updateRetryingOnce(final Map<String, ?> parameters) {
        try {
            jdbcTemplate.update(INCREMENT, parameters);
        } catch (final DataIntegrityViolationException exception) {
            jdbcTemplate.update(INCREMENT, parameters);
        }
    }

}
