package cc.hrva.urlshortener.configuration.properties;

import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private static final String KEY_MATERIAL = "private-key-material";

    @Test
    void toStringNeverContainsTheMcpSigningKey() {
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        appProperties.getMcp().setSigningKey("{\"kty\":\"RSA\",\"d\":\"" + KEY_MATERIAL + "\"}");

        assertThat(appProperties.getMcp().toString())
                .contains("https://hrva.cc")
                .doesNotContain(KEY_MATERIAL);
        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL);
    }

    @Test
    void toStringNeverContainsTheSpamhausDqsKey() {
        final var appProperties = new AppProperties();
        appProperties.setSpamhausDqsKey(KEY_MATERIAL);

        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL);
    }

    @Test
    void toStringNeverContainsTheDiscordWebhookUrl() {
        final var appProperties = new AppProperties();
        appProperties.setDiscordWebhookUrl("https://discord.com/api/webhooks/1234567890/" + KEY_MATERIAL);

        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL).doesNotContain("discord.com");
    }

    @Test
    void analyticsZoneDefaultsToZagreb() {
        assertThat(new AppProperties().getAnalytics().getZone()).isEqualTo(ZoneId.of("Europe/Zagreb"));
    }

    @Test
    void analyticsZoneBindsFromText() {
        final var binder = new Binder(new MapConfigurationPropertySource(Map.of("app.analytics.zone", "UTC")));

        assertThat(binder.bind("app.analytics", AppProperties.Analytics.class).get().getZone()).isEqualTo(ZoneId.of("UTC"));
    }

}
