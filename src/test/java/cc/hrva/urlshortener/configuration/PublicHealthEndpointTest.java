package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(
        classes = PublicHealthEndpointTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.datasource.url=jdbc:h2:mem:public-health;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "spring.mail.host=127.0.0.1",
                "management.health.redis.enabled=false",
                "management.endpoint.health.show-components=always"
        })
class PublicHealthEndpointTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @MockitoSpyBean
    private DataSource dataSource;

    @AfterEach
    void restoreTheDataSource() {
        reset(dataSource);
    }

    @Test
    void theHealthEndpointChecksTheDatabaseAndNeverOpensAMailConnection() {
        final var health = (CompositeHealthDescriptor) healthEndpoint.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getComponents()).containsKey("db").doesNotContainKey("mail");
    }

    @Test
    void theHealthEndpointReportsDownWhenTheDatabaseIsDown() throws SQLException {
        doThrow(new SQLException("database is down")).when(dataSource).getConnection();

        final var health = (CompositeHealthDescriptor) healthEndpoint.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getComponents().get("db").getStatus()).isEqualTo(Status.DOWN);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    static class TestApplication {
    }

}
