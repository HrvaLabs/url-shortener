package cc.hrva.urlshortener.util;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.TransactionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

public final class DatabaseOutages {

    public static final String UNREACHABLE = "hrva.cc can't reach its database right now. Try again in a minute.";
    public static final String OUTAGE = "outage";
    public static final String DATABASE = "database";

    private static final int MAX_CAUSES = 10;
    private static final String CONNECTION_EXCEPTION = "08";

    private DatabaseOutages() {}

    public static ProblemDetail problem() {
        final var problem = ProblemResponses.of(HttpStatus.SERVICE_UNAVAILABLE, UNREACHABLE);
        problem.setProperty(OUTAGE, DATABASE);
        return problem;
    }

    public static boolean isOutage(final Throwable failure) {
        var current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (current instanceof CannotCreateTransactionException || current instanceof DataAccessResourceFailureException
                    || current instanceof QueryTimeoutException || current instanceof TransientDataAccessResourceException
                    || current instanceof SQLTransientConnectionException || current instanceof SQLTimeoutException) {
                return true;
            }
            if (current instanceof TransactionException || current instanceof SQLException sql && StringUtils.startsWith(sql.getSQLState(), CONNECTION_EXCEPTION)) {
                return true;
            }
            if (current instanceof TransactionSystemException rollback && rollback.getApplicationException() != null
                    && isOutage(rollback.getApplicationException())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
