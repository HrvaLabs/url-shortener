package cc.hrva.urlshortener.service.stats;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void currentIsTheNewestReleaseWithoutTheV() {
        assertThat(appVersion("{\"releases\":[{\"version\":\"v2.9\"},{\"version\":\"v2.8.1\"}]}").current()).isEqualTo("2.9");
    }

    @Test
    void aVersionWithoutAVIsKept() {
        assertThat(appVersion("{\"releases\":[{\"version\":\"3.0\"}]}").current()).isEqualTo("3.0");
    }

    @Test
    void anUnreadableChangelogIsUnknown() {
        assertThat(appVersion("not json").current()).isEqualTo("unknown");
    }

    @Test
    void theShippedChangelogHasAVersion() {
        assertThat(new AppVersion(jsonMapper).current())
                .isNotBlank()
                .doesNotStartWith("v")
                .isNotEqualTo("1.4.2")
                .isNotEqualTo("unknown");
    }

    @Test
    void springCreatesItWithTheJsonMapper() {
        new ApplicationContextRunner()
                .withBean(JsonMapper.class, () -> jsonMapper)
                .withUserConfiguration(AppVersion.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppVersion.class).current()).isNotEqualTo("unknown");
                });
    }

    private AppVersion appVersion(final String changelog) {
        return new AppVersion(jsonMapper, new ByteArrayResource(changelog.getBytes(StandardCharsets.UTF_8)));
    }

}
