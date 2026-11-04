package cc.hrva.urlshortener.service.stats;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ServicePings {

    private static final int QUERY_TIMEOUT_SECONDS = 2;
    private static final double NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);
    private static final String PONG = "PONG";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public ServicePings(final DataSource dataSource, final StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.redisTemplate = redisTemplate;
    }

    public Ping database() {
        return timed("database", () -> Integer.valueOf(1).equals(jdbcTemplate.queryForObject("SELECT 1", Integer.class)));
    }

    public Ping redis() {
        return timed("Redis", () -> PONG.equalsIgnoreCase(redisTemplate.execute((RedisCallback<String>) RedisConnection::ping)));
    }

    public Pool databasePool() {
        try {
            if (!dataSource.isWrapperFor(HikariDataSource.class)) {
                return Pool.UNKNOWN;
            }
            final var hikari = dataSource.unwrap(HikariDataSource.class);
            final var pool = hikari.getHikariPoolMXBean();
            return new Pool(pool == null ? null : pool.getActiveConnections(), hikari.getMaximumPoolSize());
        } catch (final SQLException | RuntimeException exception) {
            log.warn("Could not read the database pool exception={}", exception.getClass().getName());
            return Pool.UNKNOWN;
        }
    }

    private static Ping timed(final String service, final Supplier<Boolean> ping) {
        final var startedAt = System.nanoTime();
        try {
            final var answered = Boolean.TRUE.equals(ping.get());
            final var latencyMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND;
            return answered ? new Ping(true, latencyMs) : Ping.DOWN;
        } catch (final RuntimeException exception) {
            log.warn("The {} ping failed exception={}", service, exception.getClass().getName());
            return Ping.DOWN;
        }
    }

    public record Ping(boolean up, Double latencyMs) {

        static final Ping DOWN = new Ping(false, null);

    }

    public record Pool(Integer active, Integer max) {

        static final Pool UNKNOWN = new Pool(null, null);

    }

}
