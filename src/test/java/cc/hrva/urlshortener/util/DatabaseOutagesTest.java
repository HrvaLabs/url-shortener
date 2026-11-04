package cc.hrva.urlshortener.util;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseOutagesTest {

    @Test
    void aRedisFailureIsTheCacheNotTheDatabase() {
        final var refused = new RedisConnectionFailureException("Unable to connect to Redis", new RedisConnectionException("refused"));
        final var timedOut = new QueryTimeoutException("Redis command timed out", new RedisCommandTimeoutException("Command timed out after 3 second(s)"));
        final var system = new RedisSystemException("Error in execution", new IllegalStateException("x"));

        for (final var failure : java.util.List.of(refused, timedOut, system, new IllegalStateException("wrapped", refused))) {
            assertThat(DatabaseOutages.isRedis(failure)).as(failure.toString()).isTrue();
            assertThat(DatabaseOutages.isOutage(failure)).as(failure.toString()).isFalse();
        }
    }

    @Test
    void aDatabaseFailureIsNotTheCache() {
        final var noConnection = new CannotCreateTransactionException("Could not open JPA EntityManager",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available"));

        assertThat(DatabaseOutages.isOutage(noConnection)).isTrue();
        assertThat(DatabaseOutages.isRedis(noConnection)).isFalse();
        assertThat(DatabaseOutages.isOutage(new DataAccessResourceFailureException("I/O error"))).isTrue();
        assertThat(DatabaseOutages.isRedis(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    void theCacheProblemSaysCacheAndCarriesItsOutage() {
        final var problem = DatabaseOutages.cacheProblem();

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).isEqualTo("hrva.cc can't reach its cache right now. Try again in a minute.");
        assertThat(problem.getProperties()).containsEntry("outage", "cache");
        assertThat(DatabaseOutages.problem().getProperties()).containsEntry("outage", "database");
    }

}
