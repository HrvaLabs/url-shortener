package cc.hrva.urlshortener.configuration;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AppCacheConfigurationTest {

    @Test
    void aCachedLinkIsForgottenAfterAnHour() {
        final var cacheConfiguration = new AppCacheConfiguration(JsonMapper.builder().build()).cacheConfiguration();

        assertThat(cacheConfiguration.getTtlFunction().getTimeToLive("promo", null)).isEqualTo(Duration.ofHours(1));
    }

}
