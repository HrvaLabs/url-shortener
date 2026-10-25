package cc.hrva.urlshortener.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShortUrlUniqueIndex {

    private static final String FIND_EXEC_TYPE = "select exectype from databasechangelog where id = '027-url-short-url-unique'";
    private static final String EXECUTED = "EXECUTED";
    private static final String INDEX_NAME = "UX_URL_SHORT_URL";

    private final JdbcTemplate jdbcTemplate;

    public static boolean violatedBy(final DataIntegrityViolationException exception) {
        final var violation = ExceptionUtils.throwableOfType(exception, ConstraintViolationException.class);
        return violation != null && StringUtils.containsIgnoreCase(violation.getConstraintName(), INDEX_NAME);
    }

    public boolean enforced() {
        try {
            return EXECUTED.equals(jdbcTemplate.queryForObject(FIND_EXEC_TYPE, String.class));
        } catch (final DataAccessException exception) {
            log.debug("could not read 027-url-short-url-unique from databasechangelog", exception);
            return false;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfNotEnforced() {
        if (!enforced()) {
            log.warn("short_url uniqueness not enforced: duplicate short URLs exist, so 027-url-short-url-unique was skipped");
        }
    }

}
