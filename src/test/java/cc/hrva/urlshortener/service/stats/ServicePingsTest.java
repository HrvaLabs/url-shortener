package cc.hrva.urlshortener.service.stats;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServicePingsTest {

    private final HikariDataSource dataSource = h2();
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ServicePings servicePings = new ServicePings(dataSource, redisTemplate);

    @AfterEach
    void close() {
        dataSource.close();
    }

    @Test
    void aDatabaseThatAnswersSelectOneIsUpWithItsLatency() {
        final var ping = servicePings.database();

        assertThat(ping.up()).isTrue();
        assertThat(ping.latencyMs()).isNotNegative();
    }

    @Test
    void aDatabaseThatFailsIsDownWithoutALatency() throws SQLException {
        final var broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("Connection refused"));

        final var ping = new ServicePings(broken, redisTemplate).database();

        assertThat(ping).isEqualTo(new ServicePings.Ping(false, null));
    }

    @Test
    void theHikariPoolShowsConnectionsInUseAndItsMaximum() {
        servicePings.database();

        assertThat(servicePings.databasePool()).isEqualTo(new ServicePings.Pool(0, 4));
    }

    @Test
    void aDataSourceThatIsNotHikariHasNoPoolNumbers() throws SQLException {
        final var other = mock(DataSource.class);
        when(other.isWrapperFor(HikariDataSource.class)).thenReturn(false);

        assertThat(new ServicePings(other, redisTemplate).databasePool()).isEqualTo(new ServicePings.Pool(null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisThatAnswersPongIsUp() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");

        final var ping = servicePings.redis();

        assertThat(ping.up()).isTrue();
        assertThat(ping.latencyMs()).isNotNegative();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisThatFailsIsDown() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(servicePings.redis()).isEqualTo(new ServicePings.Ping(false, null));
    }

    private static HikariDataSource h2() {
        final var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:service-pings;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

}
