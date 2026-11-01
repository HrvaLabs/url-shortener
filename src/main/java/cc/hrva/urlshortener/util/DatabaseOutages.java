package cc.hrva.urlshortener.util;

import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

public final class DatabaseOutages {

    public static final String UNREACHABLE = "hrva.cc can't reach its database right now. Try again in a minute.";

    private static final int MAX_CAUSES = 10;

    private DatabaseOutages() {}

    public static boolean isOutage(final Throwable failure) {
        var current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (current instanceof CannotCreateTransactionException || current instanceof DataAccessResourceFailureException
                    || current instanceof QueryTimeoutException || current instanceof TransientDataAccessResourceException
                    || current instanceof SQLTransientConnectionException || current instanceof SQLTimeoutException) {
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
