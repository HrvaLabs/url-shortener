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

    private static final String COUNT_INDEX_CHANGESETS = """
            select count(*) from databasechangelog
            where (id = '027-url-short-url-unique' and exectype = 'EXECUTED')
               or (id = '030-url-short-url-unique' and exectype in ('EXECUTED', 'MARK_RAN'))""";
    private static final String INDEX_NAME = "UX_URL_SHORT_URL";

    private final JdbcTemplate jdbcTemplate;

    public static boolean violatedBy(final DataIntegrityViolationException exception) {
        final var violation = ExceptionUtils.throwableOfType(exception, ConstraintViolationException.class);
        return violation != null && StringUtils.containsIgnoreCase(violation.getConstraintName(), INDEX_NAME);
    }

    public boolean enforced() {
        try {
            final var indexChangesets = jdbcTemplate.queryForObject(COUNT_INDEX_CHANGESETS, Integer.class);
            return indexChangesets != null && indexChangesets > 0;
        } catch (final DataAccessException exception) {
            log.debug("could not read the short_url index changesets from databasechangelog", exception);
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
