package cc.hrva.urlshortener.configuration;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorExposureTest {

    @Test
    void onlyHealthAndPrometheusAreExposedOverHttp() throws IOException {
        final var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(sources).anySatisfy(source ->
                assertThat(source.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus"));
    }

    @Test
    void theHealthProbesAreOnSoTheContainerCheckDoesNotDependOnMailRedisOrTheDatabase() throws IOException {
        final var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(sources).anySatisfy(source ->
                assertThat(source.getProperty("management.endpoint.health.probes.enabled")).isEqualTo(true));
    }

}
