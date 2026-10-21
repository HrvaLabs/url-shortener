package cc.hrva.urlshortener.configuration;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingPropertiesTest {

    @Test
    void scheduledJobsRunOnFourThreadsSoALongRefreshCannotStallTheOthers() throws IOException {
        final var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(sources).anySatisfy(source -> assertThat(source.getProperty("spring.task.scheduling.pool.size")).isEqualTo(4));
    }

}
