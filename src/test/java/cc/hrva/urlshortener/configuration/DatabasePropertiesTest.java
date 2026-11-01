package cc.hrva.urlshortener.configuration;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabasePropertiesTest {

    private final DatabaseTimeoutConfiguration.PostgresSocketTimeout socketTimeout = new DatabaseTimeoutConfiguration.PostgresSocketTimeout();

    @Test
    void aStuckDatabaseHoldsARequestForAConnectionAtMostThreeSeconds() throws IOException {
        final var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(sources).anySatisfy(source -> {
            assertThat(source.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo(3000);
            assertThat(source.getProperty("spring.datasource.hikari.validation-timeout")).isEqualTo(2000);
        });
    }

    @Test
    void aPostgresConnectionGivesUpOnAnAnswerAfterTwoMinutesOutsideATimedTransaction() {
        final var postgres = new HikariDataSource();
        postgres.setJdbcUrl("jdbc:postgresql://db.internal:5432/url_shortener");

        socketTimeout.postProcessAfterInitialization(postgres, "dataSource");

        assertThat(postgres.getDataSourceProperties()).containsEntry("socketTimeout", "120").containsEntry("cancelSignalTimeout", "2");
    }

    @Test
    void aTimeoutAlreadyInTheAddressIsKeptAndOtherDatabasesAreLeftAlone() {
        final var configured = new HikariDataSource();
        configured.setJdbcUrl("jdbc:postgresql://db.internal:5432/url_shortener?socketTimeout=10&cancelSignalTimeout=5");
        final var h2 = new HikariDataSource();
        h2.setJdbcUrl("jdbc:h2:mem:test");

        socketTimeout.postProcessAfterInitialization(configured, "dataSource");
        socketTimeout.postProcessAfterInitialization(h2, "dataSource");
        socketTimeout.postProcessAfterInitialization("not a data source", "other");

        assertThat(configured.getDataSourceProperties()).doesNotContainKeys("socketTimeout", "cancelSignalTimeout");
        assertThat(h2.getDataSourceProperties()).isEmpty();
    }

}
