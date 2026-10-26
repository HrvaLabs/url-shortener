package cc.hrva.urlshortener.configuration.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void passkeysBindFromCommaSeparatedOrigins() {
        final var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.passkeys.rp-id", "hrva.cc",
                "app.passkeys.allowed-origins", "https://app.hrva.cc,http://localhost:3000")));

        final var passkeys = binder.bind("app.passkeys", AppProperties.Passkeys.class).get();

        assertThat(passkeys.getRpId()).isEqualTo("hrva.cc");
        assertThat(passkeys.getAllowedOrigins()).containsExactly("https://app.hrva.cc", "http://localhost:3000");
    }

    @Test
    void passkeysBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource("environment", Map.<String, Object>of(
                "APP_PASSKEYS_RP_ID", "hrva.cc",
                "APP_PASSKEYS_ALLOWED_ORIGINS", "https://app.hrva.cc,https://hrva.cc"));
        final var binder = new Binder(ConfigurationPropertySources.from(environment));

        final var passkeys = binder.bind("app.passkeys", AppProperties.Passkeys.class).get();

        assertThat(passkeys.getRpId()).isEqualTo("hrva.cc");
        assertThat(passkeys.getAllowedOrigins()).containsExactly("https://app.hrva.cc", "https://hrva.cc");
    }

    @Test
    void weeklyDigestIsOnByDefault() {
        final var weekly = new AppProperties().getDigest().getWeekly();

        assertThat(weekly.isEnabled()).isTrue();
        assertThat(weekly.isEmail()).isTrue();
    }

    @Test
    void weeklyDigestSwitchesBindSeparately() {
        final var discordOff = new Binder(new MapConfigurationPropertySource(Map.of("app.digest.weekly.enabled", "false")))
                .bind("app.digest", AppProperties.Digest.class).get().getWeekly();
        final var emailOff = new Binder(new MapConfigurationPropertySource(Map.of("app.digest.weekly.email", "false")))
                .bind("app.digest", AppProperties.Digest.class).get().getWeekly();

        assertThat(discordOff.isEnabled()).isFalse();
        assertThat(discordOff.isEmail()).isTrue();
        assertThat(emailOff.isEnabled()).isTrue();
        assertThat(emailOff.isEmail()).isFalse();
    }

    @Test
    void weeklyDigestSwitchesBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_DIGEST_WEEKLY_ENABLED", "false",
                "APP_DIGEST_WEEKLY_EMAIL", "true"));

        final var weekly = new Binder(ConfigurationPropertySources.from(environment)).bind("app.digest", AppProperties.Digest.class).get().getWeekly();

        assertThat(weekly.isEnabled()).isFalse();
        assertThat(weekly.isEmail()).isTrue();
    }

    @Test
    void theApplicationYamlTurnsBothDigestSwitchesOn() throws IOException {
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(yaml).hasSize(1);
        assertThat(yaml.getFirst().getProperty("app.digest.weekly.enabled")).isEqualTo(true);
        assertThat(yaml.getFirst().getProperty("app.digest.weekly.email")).isEqualTo(true);
    }

    @Test
    void burstDefaultsToFiveLinksInAnHour() {
        final var burst = new AppProperties().getBurst();

        assertThat(burst.getLinks()).isEqualTo(5);
        assertThat(burst.getWindow()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void aBurstThresholdBelowTwoIsRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var one = new AppProperties.Burst();
        one.setLinks(1);
        final var two = new AppProperties.Burst();
        two.setLinks(2);
        final var noWindow = new AppProperties.Burst();
        noWindow.setWindow(null);

        assertThat(validator.validate(one)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("links");
        assertThat(validator.validate(two)).isEmpty();
        assertThat(validator.validate(noWindow)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("window");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT-1H", "PT0.0005S"})
    void aBurstWindowThatIsNotPositiveIsRefused(final String window) {
        final var burst = new AppProperties.Burst();
        burst.setWindow(Duration.parse(window));

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(burst))
                .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .containsExactly("window must be positive (at least 1ms)");
    }

    @Test
    void aBurstWindowOfOneMillisecondIsAllowed() {
        final var burst = new AppProperties.Burst();
        burst.setWindow(Duration.ofMillis(1));

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(burst)).isEmpty();
    }

    @Test
    void aZeroBurstWindowStopsTheBindingWithAClearMessage() {
        final var source = new MapConfigurationPropertySource(Map.of("app.burst.links", "5", "app.burst.window", "0s"));
        final var validator = new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> new Binder(source).bind("app.burst", Bindable.of(AppProperties.Burst.class), new ValidationBindHandler(validator)))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("app.burst.window")
                .hasMessageContaining("must be positive (at least 1ms)");
    }

    @Test
    void theBurstSettingsAreValidatedWithTheRestOfTheApp() {
        final var appProperties = new AppProperties();
        appProperties.getBurst().setLinks(1);
        appProperties.getBurst().setWindow(Duration.ZERO);

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(appProperties))
                .extracting(ConstraintViolation::getPropertyPath).map(Object::toString).contains("burst.links", "burst.window");
    }

    @Test
    void burstSettingsBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_BURST_LINKS", "8",
                "APP_BURST_WINDOW", "30m"));

        final var burst = new Binder(ConfigurationPropertySources.from(environment)).bind("app.burst", AppProperties.Burst.class).get();

        assertThat(burst.getLinks()).isEqualTo(8);
        assertThat(burst.getWindow()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void theApplicationYamlSetsFiveLinksInAnHour() throws IOException {
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(yaml.getFirst().getProperty("app.burst.links")).isEqualTo(5);
        assertThat(yaml.getFirst().getProperty("app.burst.window")).isEqualTo("1h");
    }

    @Test
    void analyticsZoneBindsFromText() {
        final var binder = new Binder(new MapConfigurationPropertySource(Map.of("app.analytics.zone", "UTC")));

        assertThat(binder.bind("app.analytics", AppProperties.Analytics.class).get().getZone()).isEqualTo(ZoneId.of("UTC"));
    }

}
