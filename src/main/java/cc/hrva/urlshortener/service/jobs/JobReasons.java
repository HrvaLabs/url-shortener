package cc.hrva.urlshortener.service.jobs;

import cc.hrva.urlshortener.util.DatabaseOutages;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.function.Predicate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mail.MailException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

final class JobReasons {

    static final String DATABASE = "The database wasn't reachable.";
    static final String REDIS = "Redis wasn't reachable.";
    static final String EMAIL = "The email server didn't accept the email.";
    static final String NO_ANSWER = "An outside service didn't answer.";
    private static final String ERROR_ANSWER = "An outside service answered with an error (HTTP %d).";
    private static final int MAX_CAUSES = 10;

    private JobReasons() {
    }

    static String of(final Throwable throwable) {
        if (throwable instanceof JobFailedException failed) {
            return failed.getMessage();
        }
        if (anyCause(throwable, cause -> cause instanceof RedisConnectionFailureException || cause instanceof RedisConnectionException
                || cause instanceof RedisCommandTimeoutException)) {
            return REDIS;
        }
        if (DatabaseOutages.isOutage(throwable)) {
            return DATABASE;
        }
        if (anyCause(throwable, cause -> cause instanceof MailException || cause instanceof MessagingException)) {
            return EMAIL;
        }
        final var answer = causeOf(throwable, RestClientResponseException.class);
        if (answer != null) {
            return ERROR_ANSWER.formatted(answer.getStatusCode().value());
        }
        if (anyCause(throwable, cause -> cause instanceof ResourceAccessException || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException || cause instanceof UnknownHostException || cause instanceof HttpTimeoutException)) {
            return NO_ANSWER;
        }
        return null;
    }

    private static boolean anyCause(final Throwable throwable, final Predicate<Throwable> test) {
        var current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (test.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T extends Throwable> T causeOf(final Throwable throwable, final Class<T> type) {
        var current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

}
